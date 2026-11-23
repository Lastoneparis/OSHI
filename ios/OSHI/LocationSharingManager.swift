//
//  LocationSharingManager.swift
//  OSHI
//
//  Location sharing functionality (WhatsApp-style)
//  Supports one-time and live location sharing
//

import Foundation
import CoreLocation
import MapKit
import Combine

// MARK: - Brand Color
private let appBrandColor = UIColor(red: 0.337, green: 0.086, blue: 0.925, alpha: 1.0)

// MARK: - Location Manager

class LocationSharingManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = LocationSharingManager()
    
    private let locationManager = CLLocationManager()
    
    @Published var currentLocation: CLLocation?
    @Published var authorizationStatus: CLAuthorizationStatus = .notDetermined
    @Published var isUpdatingLocation = false
    @Published var locationError: String?
    
    // Live sharing state
    //
    // __MULTI_LIVE_SHARE_2026_09_22__ Plusieurs partages en même temps.
    //
    // Avant: UN seul partage. En démarrer un second avec un autre contact
    // arrêtait le premier en silence (« Stopping previous live share ») — le
    // premier contact voyait sa bulle passer à « terminé » sans que personne ne
    // l'ait demandé. Chaque conversation a maintenant sa propre session: sa
    // propre heure de fin, son propre minuteur d'expiration, ses propres rappels.
    // UN seul minuteur de 10 s pousse la position à toutes les sessions actives,
    // et le GPS ne s'arrête qu'à la fin de la DERNIÈRE.
    //
    // Les propriétés historiques (`isLiveSharing`, `liveShareEndTime`, …)
    // restent publiées et décrivent le partage le plus RÉCENT, pour que rien de
    // ce qui les lit ne change de sens; ce qui a besoin d'une conversation
    // précise passe par `liveShare(for:)` / `liveShare(sessionId:)`.
    struct ActiveLiveShare: Identifiable, Equatable {
        let conversationId: String
        let sessionId: String
        let startTime: Date
        var endTime: Date
        var id: String { sessionId }
        var remaining: TimeInterval { max(0, endTime.timeIntervalSinceNow) }
    }

    /// Every share currently running, most recent LAST.
    @Published private(set) var activeLiveShares: [ActiveLiveShare] = []

    @Published var isLiveSharing = false
    @Published var liveShareStartTime: Date?
    @Published var liveShareEndTime: Date?
    @Published var liveShareConversationId: String?
    @Published var liveShareMessageId: String?  // Track which message is the active live share

    private struct SessionCallbacks {
        let onUpdate: (CLLocation) -> Void
        let onStop: (() -> Void)?
        var endTimer: Timer?
    }
    /// Keyed by sessionId.
    private var sessionCallbacks: [String: SessionCallbacks] = [:]
    private var liveUpdateTimer: Timer?
    
    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = 10 // Update every 10 meters

        // Get initial status
        authorizationStatus = locationManager.authorizationStatus
        OshiLog.maps.info("📍 LocationSharingManager initialized")
        OshiLog.maps.info("📍 Initial authorization status: \(authorizationStatus.rawValue) (\(statusName(authorizationStatus)))")

        // 🔧 FIX (2026-06-03): re-evaluate live-share expiry on every return
        // to foreground. `liveShareTimer` (Timer.scheduledTimer in
        // startLiveSharingWithCustomDuration) is paused while the app is
        // suspended — if the user backgrounds the app for the entire share
        // duration, the timer never fires and the share keeps running past its
        // intended expiry. This observer catches that case and calls
        // stopLiveSharing() immediately on resume if Date() has passed
        // liveShareEndTime.
        NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self = self else { return }
            // (a) Self-heal our OWN expired live share.
            self.stopExpiredLiveShares()
            // (b) Sweep PEER entries (Bug 5 dict) that expired while suspended.
            self.pruneExpiredPeerLocations()
        }

        // NOTE: Do NOT request permission here - iOS requires visible UI
        // Permission will be requested when user taps the location button
    }
    
    /// Call this on app launch to ensure location system is properly registered
    /// This does NOT request permission - just prepares the system
    func registerWithLocationServices() {
        OshiLog.maps.info("📍 Registering app with iOS Location Services...")
        let status = locationManager.authorizationStatus
        OshiLog.maps.info("📍 Current status: \(status.rawValue) (\(statusName(status)))")
        
        // Update our published status
        DispatchQueue.main.async {
            self.authorizationStatus = status
        }
        
        // NOTE: Do NOT auto-request permission here
        // iOS only shows the dialog when app is in foreground with visible UI
        // Permission will be requested when user explicitly asks for location
    }
    
    // MARK: - Permission Handling
    
    private var hasRequestedPermissionThisSession = false
    
    func requestPermission() {
        guard !hasRequestedPermissionThisSession else {
            OshiLog.maps.info("📍 Already requested permission this session, skipping duplicate request")
            return
        }
        hasRequestedPermissionThisSession = true
        OshiLog.maps.info("📍 Requesting location permission...")
        locationManager.requestWhenInUseAuthorization()
    }
    
    /// Request permission from UI - this is the main method to request location permission
    /// IMPORTANT: This must be called from a visible UI with the app in foreground
    func requestPermissionForUI() {
        OshiLog.maps.info("📍 ════════════════════════════════════════════")
        OshiLog.maps.info("📍 [UI] REQUESTING LOCATION PERMISSION")
        OshiLog.maps.info("📍 ════════════════════════════════════════════")
        OshiLog.maps.info("📍 [UI] Device: \(UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone")")
        OshiLog.maps.info("📍 [UI] System: \(UIDevice.current.systemName) \(UIDevice.current.systemVersion)")
        
        // Get current status from the manager
        let currentStatus = locationManager.authorizationStatus
        OshiLog.maps.info("📍 [UI] Current status: \(currentStatus.rawValue) (\(statusName(currentStatus)))")
        
        // Update our published status
        DispatchQueue.main.async {
            self.authorizationStatus = currentStatus
        }
        
        // If already authorized, just report it
        if currentStatus == .authorizedWhenInUse || currentStatus == .authorizedAlways {
            OshiLog.maps.info("📍 [UI] ✅ Already authorized!")
            return
        }
        
        // If denied or restricted, can't request again programmatically
        if currentStatus == .denied {
            OshiLog.maps.info("📍 [UI] ⚠️ Permission DENIED - user must enable in Settings")
            OshiLog.maps.info("📍 [UI] Go to: Settings > Privacy & Security > Location Services > OSHI")
            return
        }
        
        if currentStatus == .restricted {
            OshiLog.maps.info("📍 [UI] ⚠️ Permission RESTRICTED - parental controls or MDM")
            return
        }
        
        // Check if Location Services is enabled globally
        guard CLLocationManager.locationServicesEnabled() else {
            OshiLog.maps.info("📍 [UI] ⚠️ Location Services is DISABLED globally on device")
            OshiLog.maps.info("📍 [UI] Go to: Settings > Privacy & Security > Location Services")
            return
        }
        
        // Status is notDetermined - request permission
        // CRITICAL: This is where the iOS dialog should appear
        OshiLog.maps.info("📍 [UI] Status is notDetermined - requesting authorization NOW...")
        
        // Configure for best results
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = kCLDistanceFilterNone
        
        // IMPORTANT: Only call requestWhenInUseAuthorization()
        // Do NOT call startUpdatingLocation() or requestLocation() before permission!
        // Calling these before permission is granted causes immediate CLError.denied
        OshiLog.maps.info("📍 [UI] >>> Calling requestWhenInUseAuthorization() <<<")
        locationManager.requestWhenInUseAuthorization()
        
        OshiLog.maps.info("📍 [UI] Permission request sent to iOS")
        OshiLog.maps.info("📍 [UI] Waiting for user response...")
    }
    
    /// Try to trigger the permission dialog - DEPRECATED, use FreshLocationPermissionRequester instead
    func triggerPermissionDialog() {
        OshiLog.maps.info("📍 [UI] triggerPermissionDialog called - redirecting to requestWhenInUseAuthorization")
        // Don't call startUpdatingLocation before permission!
        locationManager.requestWhenInUseAuthorization()
    }
    
    /// Stop location updates (call after permission granted or denied)
    func stopLocationUpdates() {
        locationManager.stopUpdatingLocation()
    }

    /// Foreground-only continuous updates for the Map view "follow me" UX.
    /// Crucially: this does NOT set `allowsBackgroundLocationUpdates = true`,
    /// so when the app moves to background iOS pauses updates and no
    /// background-location notification is generated. Pair with
    /// `stopForegroundLocationUpdates()` on view disappear.
    func startForegroundLocationUpdates() {
        guard isAuthorized else {
            OshiLog.maps.info("📍 [foreground] not authorized — skipping startUpdatingLocation")
            return
        }
        // Hard-guarantee background is OFF before starting. Prevents a
        // future caller from leaking background mode into this path.
        locationManager.allowsBackgroundLocationUpdates = false
        locationManager.pausesLocationUpdatesAutomatically = true
        locationManager.showsBackgroundLocationIndicator = false
        locationManager.startUpdatingLocation()
        OshiLog.maps.info("📍 [foreground] continuous updates started (foreground only)")
    }

    /// Stop the foreground-only updates started by `startForegroundLocationUpdates`.
    /// Safe to call even if updates aren't running.
    func stopForegroundLocationUpdates() {
        locationManager.stopUpdatingLocation()
        OshiLog.maps.info("📍 [foreground] continuous updates stopped")
    }
    
    /// Open app's specific location settings
    func openAppLocationSettings() {
        if let appSettingsURL = URL(string: UIApplication.openSettingsURLString) {
            OshiLog.maps.info("📍 Opening app settings: \(appSettingsURL)")
            UIApplication.shared.open(appSettingsURL) { success in
                OshiLog.maps.info("📍 Settings opened: \(success)")
            }
        }
    }
    
    /// Open system Location Services settings (iOS 15.4+)
    func openLocationServicesSettings() {
        // Try to open Location Services directly (works on some iOS versions)
        if let locationSettingsURL = URL(string: "App-Prefs:root=Privacy&path=LOCATION") {
            if UIApplication.shared.canOpenURL(locationSettingsURL) {
                OshiLog.maps.info("📍 Opening Location Services settings directly")
                UIApplication.shared.open(locationSettingsURL)
                return
            }
        }
        
        // Fallback to app settings
        OshiLog.maps.info("📍 Falling back to app settings")
        openAppLocationSettings()
    }
    
    /// Force refresh authorization status from system
    func refreshAuthorizationStatus() {
        // Use the instance property which doesn't block the main thread
        let newStatus = locationManager.authorizationStatus
        OshiLog.maps.info("📍 Refreshing status: current=\(authorizationStatus.rawValue), system=\(newStatus.rawValue)")
        
        // Always update on main thread
        if Thread.isMainThread {
            if newStatus != authorizationStatus {
                self.authorizationStatus = newStatus
            }
        } else {
            DispatchQueue.main.async {
                if newStatus != self.authorizationStatus {
                    self.authorizationStatus = newStatus
                }
            }
        }
    }
    
    /// Async check for location services - avoids main thread blocking
    func checkLocationServicesEnabled(completion: @escaping (Bool) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            let enabled = CLLocationManager.locationServicesEnabled()
            DispatchQueue.main.async {
                completion(enabled)
            }
        }
    }
    
    /// Force a complete refresh of authorization by creating a request
    func forceRefreshAuthorization() {
        OshiLog.maps.info("📍 Force refreshing authorization...")
        // Getting the status from the manager triggers the delegate
        let status = locationManager.authorizationStatus
        OshiLog.maps.info("📍 Force refresh got status: \(status.rawValue) (\(statusName(status)))")
        
        DispatchQueue.main.async {
            self.authorizationStatus = status
        }
        
        // If authorized, try to get a location to confirm
        if status == .authorizedWhenInUse || status == .authorizedAlways {
            OshiLog.maps.info("📍 Authorized - requesting location to confirm...")
            locationManager.requestLocation()
        }
    }
    
    func resetPermissionSession() {
        hasRequestedPermissionThisSession = false
    }
    
    func requestAlwaysPermission() {
        OshiLog.maps.info("📍 Requesting always location permission...")
        locationManager.requestAlwaysAuthorization()
    }
    
    /// Request appropriate permission for live location sharing
    /// For live location, we need "Always" to update in background
    func requestPermissionForLiveLocation(completion: @escaping (Bool) -> Void) {
        OshiLog.maps.info("📍 Requesting permission for live location sharing...")
        
        let currentStatus = locationManager.authorizationStatus
        
        switch currentStatus {
        case .authorizedAlways:
            // Already have full permission
            OshiLog.maps.info("📍 Already have Always authorization")
            completion(true)
            
        case .authorizedWhenInUse:
            // Need to upgrade to Always for background updates
            OshiLog.maps.info("📍 Upgrading from WhenInUse to Always authorization...")
            // First request always, iOS will show the upgrade dialog
            locationManager.requestAlwaysAuthorization()
            // The result will come through delegate
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                completion(self.authorizationStatus == .authorizedAlways || self.authorizationStatus == .authorizedWhenInUse)
            }
            
        case .notDetermined:
            // Request WhenInUse first, then Always
            OshiLog.maps.info("📍 Requesting WhenInUse first, then Always...")
            locationManager.requestWhenInUseAuthorization()
            // Wait a moment for the dialog, then request Always
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                if self.authorizationStatus == .authorizedWhenInUse {
                    self.locationManager.requestAlwaysAuthorization()
                }
                completion(self.isAuthorized)
            }
            
        case .denied, .restricted:
            OshiLog.maps.info("📍 Location permission denied, need to open Settings")
            completion(false)
            
        @unknown default:
            completion(false)
        }
    }
    
    /// Check if we have sufficient permission for live location
    var hasAlwaysPermission: Bool {
        authorizationStatus == .authorizedAlways
    }
    
    /// Check if we need to request upgrade to Always permission for live location
    var needsAlwaysPermissionUpgrade: Bool {
        authorizationStatus == .authorizedWhenInUse
    }
    
    var isAuthorized: Bool {
        switch authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            return true
        default:
            return false
        }
    }
    
    var needsPermission: Bool {
        authorizationStatus == .notDetermined
    }
    
    var isDenied: Bool {
        authorizationStatus == .denied || authorizationStatus == .restricted
    }
    
    // MARK: - One-Time Location
    
    func getCurrentLocation(completion: @escaping (Result<CLLocation, Error>) -> Void) {
        guard isAuthorized else {
            completion(.failure(LocationError.notAuthorized))
            return
        }
        
        isUpdatingLocation = true
        locationError = nil
        
        // Request single location update
        locationManager.requestLocation()
        
        // Set up completion handler
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
            if self?.currentLocation == nil {
                self?.isUpdatingLocation = false
                completion(.failure(LocationError.timeout))
            }
        }
        
        // Wait for location update
        var cancellable: AnyCancellable?
        cancellable = $currentLocation
            .compactMap { $0 }
            .first()
            .sink { [weak self] location in
                self?.isUpdatingLocation = false
                completion(.success(location))
                cancellable?.cancel()
            }
    }
    
    // MARK: - Live Location Sharing
    
    enum LiveDuration: Int, CaseIterable {
        case twoMinutes = 2
        case fiveMinutes = 5
        case fifteenMinutes = 15
        case thirtyMinutes = 30
        case oneHour = 60
        case eightHours = 480
        
        var displayName: String {
            switch self {
            case .twoMinutes: return "location.2_minutes".localized
            case .fiveMinutes: return "location.5_minutes".localized
            case .fifteenMinutes: return "location.15_minutes".localized
            case .thirtyMinutes: return "location.30_minutes".localized
            case .oneHour: return "location.1_hour".localized
            case .eightHours: return "location.8_hours".localized
            }
        }
        
        var seconds: TimeInterval {
            TimeInterval(rawValue * 60)
        }
    }
    
    func startLiveSharing(duration: LiveDuration, conversationId: String, messageId: String? = nil, onUpdate: @escaping (CLLocation) -> Void, onStop: (() -> Void)? = nil) {
        // Use the seconds from the preset duration
        startLiveSharingWithCustomDuration(durationSeconds: duration.seconds, conversationId: conversationId, messageId: messageId, onUpdate: onUpdate, onStop: onStop)
    }

    /// Start live sharing with a custom duration in seconds (for matching sender's remaining time).
    /// `onStop` fires once when sharing ends for ANY reason (manual stop OR the
    /// timer expiring) — the caller uses it to send a `stopped` message to the
    /// peer so the recipient's bubble stops showing "live" on BOTH sides. Before
    /// this, natural expiry only posted a local event and the peer relied on a
    /// fragile local timer, so a dropped final update could leave them stuck live.
    ///
    /// Shares with OTHER conversations keep running (__MULTI_LIVE_SHARE_2026_09_22__);
    /// only a share already running in THIS conversation is replaced.
    func startLiveSharingWithCustomDuration(durationSeconds: TimeInterval, conversationId: String, messageId: String? = nil, onUpdate: @escaping (CLLocation) -> Void, onStop: (() -> Void)? = nil) {
        guard isAuthorized else {
            OshiLog.maps.info("❌ Location not authorized for live sharing")
            return
        }

        // One share per conversation: a new one here replaces the old one here.
        if let existing = liveShare(for: conversationId) {
            OshiLog.maps.info("📍 Replacing the live share already running in this conversation")
            stopLiveSharing(sessionId: existing.sessionId)
        }

        let minutes = Int(durationSeconds / 60)
        OshiLog.maps.info("📍 Starting live location sharing for \(minutes) minutes (\(Int(durationSeconds)) seconds) — \(activeLiveShares.count) other share(s) running")

        let sessionId = messageId ?? UUID().uuidString
        let now = Date()
        let share = ActiveLiveShare(conversationId: conversationId, sessionId: sessionId,
                                    startTime: now, endTime: now.addingTimeInterval(durationSeconds))

        // 🔧 FIX (2026-06-04): `Timer(timeInterval:)` + RunLoop `.common` rather
        // than `Timer.scheduledTimer`, which lands in `.default` and pauses while
        // the user scrolls and while only CLLocationManager background callbacks
        // spin the runloop. The willEnterForeground observer in init() is the
        // backstop for full suspension.
        let endT = Timer(timeInterval: durationSeconds, repeats: false) { [weak self] _ in
            self?.stopLiveSharing(sessionId: sessionId)
        }
        RunLoop.main.add(endT, forMode: .common)
        sessionCallbacks[sessionId] = SessionCallbacks(onUpdate: onUpdate, onStop: onStop, endTimer: endT)
        activeLiveShares.append(share)
        syncLegacyLiveShareState()

        // Enable background location updates for live sharing
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.showsBackgroundLocationIndicator = true
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = 10 // Update every 10 meters
        locationManager.startUpdatingLocation()

        // One 10 s cadence for ALL sessions. Delay first update by 10 s on
        // purpose — the initial location was already sent by the caller; firing
        // immediately would dupe it (the update used to arrive before the
        // initial message and create a second bubble on the receiver).
        if liveUpdateTimer == nil {
            let updateT = Timer(timeInterval: 10, repeats: true) { [weak self] _ in
                guard let self, let location = self.currentLocation else { return }
                for callbacks in self.sessionCallbacks.values {
                    callbacks.onUpdate(location)
                }
            }
            RunLoop.main.add(updateT, forMode: .common)
            liveUpdateTimer = updateT
        }

        OshiLog.maps.info("📍 Live sharing started - first update will be sent in 10 seconds")
    }

    /// Stops EVERY live share. Kept for callers that have no session in hand.
    func stopLiveSharing() {
        for share in activeLiveShares {
            stopLiveSharing(sessionId: share.sessionId)
        }
    }

    /// Stops the live share running in one conversation, if any.
    func stopLiveSharing(conversationId: String) {
        guard let share = liveShare(for: conversationId) else { return }
        stopLiveSharing(sessionId: share.sessionId)
    }

    func stopLiveSharing(sessionId: String) {
        // Nothing to do if that session is not running — prevents a double onStop
        // when both the manual Stop button and the end-timer race to call this.
        guard let index = activeLiveShares.firstIndex(where: { $0.sessionId == sessionId }) else { return }
        OshiLog.maps.info("📍 Stopping live location sharing (sessionId: \(sessionId.prefix(8))...)")

        let callbacks = sessionCallbacks.removeValue(forKey: sessionId)
        callbacks?.endTimer?.invalidate()
        activeLiveShares.remove(at: index)

        // Notify the peer that sharing ended (manual OR timer expiry) so their
        // bubble stops showing "live" too.
        callbacks?.onStop?()

        syncLegacyLiveShareState()

        if activeLiveShares.isEmpty {
            // Last share gone: give the GPS and the background indicator back.
            locationManager.allowsBackgroundLocationUpdates = false
            locationManager.showsBackgroundLocationIndicator = false
            locationManager.stopUpdatingLocation()
            liveUpdateTimer?.invalidate()
            liveUpdateTimer = nil
        }

        // 🔧 FIX: Post notification with sessionId so only the correct bubble stops
        DispatchQueue.main.async {
            NotificationCenter.default.post(
                name: NSNotification.Name("LiveLocationStopped"),
                object: nil,
                userInfo: ["sessionId": sessionId]
            )
        }
        OshiLog.maps.info("📍 Live location sharing STOPPED — \(activeLiveShares.count) still running")
    }

    /// Extend the most recent live sharing session by adding more time
    func extendLiveSharing(additionalMinutes: Int) {
        guard let share = activeLiveShares.last else {
            OshiLog.maps.info("📍 Cannot extend - not currently sharing live location")
            return
        }
        extendLiveSharing(sessionId: share.sessionId, additionalMinutes: additionalMinutes)
    }

    func extendLiveSharing(sessionId: String, additionalMinutes: Int) {
        guard let index = activeLiveShares.firstIndex(where: { $0.sessionId == sessionId }) else {
            OshiLog.maps.info("📍 Cannot extend - session \(sessionId.prefix(8)) is not live")
            return
        }

        OshiLog.maps.info("📍 Extending live location sharing by \(additionalMinutes) minutes")

        let newEndTime = activeLiveShares[index].endTime.addingTimeInterval(TimeInterval(additionalMinutes * 60))
        activeLiveShares[index].endTime = newEndTime

        // New end timer (.common mode, see startLiveSharingWithCustomDuration fix)
        sessionCallbacks[sessionId]?.endTimer?.invalidate()
        let endT = Timer(timeInterval: newEndTime.timeIntervalSinceNow, repeats: false) { [weak self] _ in
            self?.stopLiveSharing(sessionId: sessionId)
        }
        RunLoop.main.add(endT, forMode: .common)
        sessionCallbacks[sessionId]?.endTimer = endT
        syncLegacyLiveShareState()

        // Post notification so UI can update
        DispatchQueue.main.async {
            NotificationCenter.default.post(name: NSNotification.Name("LiveLocationExtended"), object: nil,
                                            userInfo: ["newEndTime": newEndTime, "sessionId": sessionId])
        }

        OshiLog.maps.info("📍 New end time: \(newEndTime)")
    }

    /// The live share running in a conversation, if any.
    func liveShare(for conversationId: String) -> ActiveLiveShare? {
        activeLiveShares.first { $0.conversationId == conversationId }
    }

    func liveShare(sessionId: String) -> ActiveLiveShare? {
        activeLiveShares.first { $0.sessionId == sessionId }
    }

    /// Stops every share whose end time passed while the app was suspended.
    private func stopExpiredLiveShares() {
        let now = Date()
        for share in activeLiveShares where share.endTime <= now {
            OshiLog.maps.info("📍 Foreground: live-share already past expiry (\(share.endTime)) — stopping now")
            stopLiveSharing(sessionId: share.sessionId)
        }
    }

    /// Mirrors the most recent share into the single-share properties.
    private func syncLegacyLiveShareState() {
        let latest = activeLiveShares.last
        isLiveSharing = latest != nil
        liveShareStartTime = latest?.startTime
        liveShareEndTime = latest?.endTime
        liveShareConversationId = latest?.conversationId
        liveShareMessageId = latest?.sessionId
    }

    var remainingLiveShareTime: TimeInterval? {
        guard let endTime = liveShareEndTime else { return nil }
        let remaining = endTime.timeIntervalSince(Date())
        return remaining > 0 ? remaining : nil
    }
    

    // MARK: - CLLocationManagerDelegate
    
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        
        OshiLog.maps.info("📍 Location updated: \(location.coordinate.latitude), \(location.coordinate.longitude)")
        
        DispatchQueue.main.async {
            self.currentLocation = location
            self.isUpdatingLocation = false

            // 🔧 Live updates are pushed by the 10s timer in startLiveSharing — NOT
            // from here. Firing the callback on every CLLocationManager delegate tick
            // (every 10 m of movement) sent a full encrypt+transmit per step while
            // walking, which lagged the UI. The timer keeps the real-time feel while
            // the freshest fix is always available via self.currentLocation.
        }
    }
    
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        OshiLog.maps.info("❌ Location error: \(error.localizedDescription)")
        
        // Check if error is due to authorization
        if let clError = error as? CLError {
            OshiLog.maps.info("📍 CLError code: \(clError.code.rawValue)")
            
            if clError.code == .denied {
                OshiLog.maps.info("📍 Location access was denied")
                DispatchQueue.main.async {
                    self.authorizationStatus = .denied
                }
            }
        }
        
        DispatchQueue.main.async {
            self.locationError = error.localizedDescription
            self.isUpdatingLocation = false
            
            // Stop updates to avoid repeated errors
            self.locationManager.stopUpdatingLocation()
        }
    }
    
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let newStatus = manager.authorizationStatus
        OshiLog.maps.info("📍 ════════════════════════════════════════════")
        OshiLog.maps.info("📍 AUTHORIZATION STATUS CHANGED")
        OshiLog.maps.info("📍 ════════════════════════════════════════════")
        OshiLog.maps.info("📍 New status: \(newStatus.rawValue) (\(statusName(newStatus)))")
        OshiLog.maps.info("📍 Previous status: \(authorizationStatus.rawValue) (\(statusName(authorizationStatus)))")
        
        DispatchQueue.main.async {
            self.authorizationStatus = newStatus
            
            // Post notification so views can react
            NotificationCenter.default.post(
                name: NSNotification.Name("LocationAuthorizationChanged"),
                object: nil,
                userInfo: ["status": newStatus.rawValue]
            )
            
            // Stop location updates if permission denied
            if newStatus == .denied || newStatus == .restricted {
                OshiLog.maps.info("📍 Permission denied/restricted - stopping location updates")
                self.locationManager.stopUpdatingLocation()
            } else if newStatus == .authorizedWhenInUse || newStatus == .authorizedAlways {
                OshiLog.maps.info("📍 ✅ Permission GRANTED: \(self.statusName(newStatus))")
                // Request a single location so the map can center on the user
                OshiLog.maps.info("📍 Requesting initial location for map centering...")
                self.locationManager.requestLocation()
            }
        }
    }
    
    private func statusName(_ status: CLAuthorizationStatus) -> String {
        switch status {
        case .notDetermined: return "notDetermined"
        case .restricted: return "restricted"
        case .denied: return "denied"
        case .authorizedAlways: return "authorizedAlways"
        case .authorizedWhenInUse: return "authorizedWhenInUse"
        @unknown default: return "unknown"
        }
    }
    
    // MARK: - Received Live Location Tracking

    /// Stores the latest received coordinate per live session ID (from other contacts)
    private var receivedLiveLocations: [String: CLLocationCoordinate2D] = [:]

    /// Called when a live location update message is received from a contact
    func updateReceivedLiveLocation(sessionId: String, coordinate: CLLocationCoordinate2D) {
        receivedLiveLocations[sessionId] = coordinate
    }

    /// Get the latest received coordinate for a live session
    func getLatestLiveCoordinate(sessionId: String) -> CLLocationCoordinate2D? {
        return receivedLiveLocations[sessionId]
    }

    /// Clean up when a live session stops
    func clearReceivedLiveLocation(sessionId: String) {
        receivedLiveLocations.removeValue(forKey: sessionId)
    }

    // MARK: - Peer-keyed live location cache (Bug 5: WhatsApp-style bidirectional dot)
    //
    // The receivedLiveLocations dict above is keyed by SESSIONID, which is
    // what OfflineMapView's full-screen live overlay needs. But to show
    // "is the peer in this 1:1 conversation actively sharing back?" on
    // Alice's OWN bubble, we need a peer-keyed view. Bob's back-share has
    // its own UUID sessionId that Alice doesn't know in advance; she only
    // knows Bob's public key (contactPublicKey on the bubble). Keeping
    // BOTH dicts side-by-side avoids breaking OfflineMapView's existing
    // sessionId-keyed consumer.
    //
    // Written from MessageManager.addMessage when a live-location message
    // arrives from a peer. Read reactively from LocationMessageBubble via
    // @Published. At most one write per ~10s per active peer — cheap.

    struct PeerLiveLocation: Equatable {
        let coordinate: CLLocationCoordinate2D
        let expiresAt: Date
        let sessionId: String

        static func == (lhs: PeerLiveLocation, rhs: PeerLiveLocation) -> Bool {
            lhs.coordinate.latitude == rhs.coordinate.latitude &&
            lhs.coordinate.longitude == rhs.coordinate.longitude &&
            lhs.expiresAt == rhs.expiresAt &&
            lhs.sessionId == rhs.sessionId
        }
    }

    /// Latest live coordinate from each peer the user is in conversation with,
    /// keyed by the peer's public key. Pruned on expiry + on explicit stop.
    @Published private(set) var liveContactLocations: [String: PeerLiveLocation] = [:]

    /// Called from MessageManager when a live-location message arrives from
    /// a peer (initial OR update). No-op if already expired or unchanged.
    func updatePeerLiveLocation(peerPublicKey: String, coordinate: CLLocationCoordinate2D, expiresAt: Date, sessionId: String) {
        guard Date() < expiresAt else { return }
        let entry = PeerLiveLocation(coordinate: coordinate, expiresAt: expiresAt, sessionId: sessionId)
        let assign: () -> Void = { [weak self] in
            guard let self = self else { return }
            if self.liveContactLocations[peerPublicKey] != entry {
                self.liveContactLocations[peerPublicKey] = entry
            }
        }
        if Thread.isMainThread { assign() } else { DispatchQueue.main.async(execute: assign) }
    }

    /// Drop the peer's entry. Used when the user knows the peer key directly.
    func clearPeerLiveLocation(peerPublicKey: String) {
        let assign: () -> Void = { [weak self] in
            self?.liveContactLocations.removeValue(forKey: peerPublicKey)
        }
        if Thread.isMainThread { assign() } else { DispatchQueue.main.async(execute: assign) }
    }

    /// Drop whichever entry matches the given sessionId. Used when a STOPPED
    /// message arrives carrying only the sessionId (no peer key in scope).
    func clearPeerLiveLocation(sessionId: String) {
        let assign: () -> Void = { [weak self] in
            guard let self = self else { return }
            for (k, v) in self.liveContactLocations where v.sessionId == sessionId {
                self.liveContactLocations.removeValue(forKey: k)
            }
        }
        if Thread.isMainThread { assign() } else { DispatchQueue.main.async(execute: assign) }
    }

    /// Sweep expired peer entries. Cheap O(k), k = simultaneous peers (usually
    /// 0 or 1). Called from the willEnterForeground observer in init() and
    /// opportunistically from the bubble's 1s display timer.
    func pruneExpiredPeerLocations() {
        let now = Date()
        let assign: () -> Void = { [weak self] in
            guard let self = self else { return }
            for (k, v) in self.liveContactLocations where v.expiresAt <= now {
                self.liveContactLocations.removeValue(forKey: k)
            }
        }
        if Thread.isMainThread { assign() } else { DispatchQueue.main.async(execute: assign) }
    }

    // MARK: - Errors

    enum LocationError: LocalizedError {
        case notAuthorized
        case timeout
        case unknown

        var errorDescription: String? {
            switch self {
            case .notAuthorized: return "Location permission not granted"
            case .timeout: return "Location request timed out"
            case .unknown: return "Unknown location error"
            }
        }
    }
}

// MARK: - Location Message Model

struct LocationMessage: Codable, Equatable {
    let latitude: Double
    let longitude: Double
    let timestamp: Date
    let isLive: Bool
    let expiresAt: Date?
    let accuracy: Double?
    let address: String?
    let sessionId: String?  // Unique ID for live sharing session
    let isUpdate: Bool      // True for live location updates (no notification)
    let isStopped: Bool     // 📍 True when sender manually stopped sharing
    
    // Marker prefix for identifying location messages
    static let messagePrefix = "📍LOCATION📍"
    // Also expose for external use
    static let locationPrefix = "📍LOCATION📍"
    
    // MARK: - Explicit Memberwise Initializer (needed because we have custom decoder)
    init(
        latitude: Double,
        longitude: Double,
        timestamp: Date,
        isLive: Bool,
        expiresAt: Date?,
        accuracy: Double?,
        address: String?,
        sessionId: String?,
        isUpdate: Bool,
        isStopped: Bool
    ) {
        self.latitude = latitude
        self.longitude = longitude
        self.timestamp = timestamp
        self.isLive = isLive
        self.expiresAt = expiresAt
        self.accuracy = accuracy
        self.address = address
        self.sessionId = sessionId
        self.isUpdate = isUpdate
        self.isStopped = isStopped
    }
    
    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
    
    var isExpired: Bool {
        // If manually stopped, it's "expired"
        if isStopped { return true }
        guard let expiresAt = expiresAt else { return false }
        return Date() > expiresAt
    }
    
    var remainingTime: TimeInterval? {
        // __LIVE_STOP_COUNTDOWN_2026_09_12__ Un partage ARRÊTÉ n'a plus de temps
        // restant, et c'est ce qui manquait ici.
        //
        // `isExpired` juste au-dessus pose déjà la question (`if isStopped { return
        // true }`); celle-ci ne la posait pas et rendait bêtement l'écart jusqu'à
        // l'`expiresAt` d'origine. Conséquence côté expéditeur: on arrête le
        // partage avant la fin, `locationManager.isLiveSharing` passe à faux, donc
        // `isMyLiveShare` passe à faux, donc `updateRemainingTime()` bascule sur
        // CETTE branche — et le compte à rebours repart tranquillement vers
        // l'heure de fin initiale, comme si rien ne s'était passé.
        if isStopped { return nil }
        guard let expiresAt = expiresAt else { return nil }
        let remaining = expiresAt.timeIntervalSince(Date())
        return remaining > 0 ? remaining : nil
    }

    /// Only the periodic live-location PINGS are silent. The two ends of a
    /// share — starting it and stopping it — are events a person wants to know
    /// about, and they happen once each.
    ///
    /// __LOCATION_PING_SPAM_2026_08_24__ This used to read `isUpdate ||
    /// isStopped`, which also swallowed the stop signal. `stopped()` is built
    /// with `isUpdate: true` (see the factory), so silencing on `isUpdate` alone
    /// silences the end of the share too — the recipient's dot just quietly
    /// stopped moving with nothing to say it had ended.
    ///
    /// Without the ping half, two people sharing live location banner each other
    /// every ~10 s: the duplicate-popup complaint. The map still follows the
    /// other person, because the recipient picks the pings up on its normal
    /// message poll — a silent ping is still a delivered message.
    var isSilentForPush: Bool { isUpdate && !isStopped }

    /// Whether a raw wire string is a silent live-location ping (update/stop).
    static func isSilentPushContent(_ content: String) -> Bool {
        guard content.hasPrefix(messagePrefix),
              let msg = fromMessageString(content) else { return false }
        return msg.isSilentForPush
    }
    
    // Encode to message string
    func toMessageString() -> String {
        guard let data = try? JSONEncoder().encode(self),
              let json = String(data: data, encoding: .utf8) else {
            return ""
        }
        return "\(Self.messagePrefix)\(json)"
    }
    
    // Decode from message string
    static func fromMessageString(_ string: String) -> LocationMessage? {
        guard string.hasPrefix(messagePrefix) else { return nil }
        let jsonString = String(string.dropFirst(messagePrefix.count))
        guard let data = jsonString.data(using: .utf8),
              let message = try? JSONDecoder().decode(LocationMessage.self, from: data) else {
            return nil
        }
        return message
    }
    
    // Create one-time location message
    static func oneTime(from location: CLLocation, address: String? = nil) -> LocationMessage {
        LocationMessage(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            timestamp: Date(),
            isLive: false,
            expiresAt: nil,
            accuracy: location.horizontalAccuracy,
            address: address,
            sessionId: nil,
            isUpdate: false,
            isStopped: false
        )
    }
    
    // Create live location message (initial)
    static func live(from location: CLLocation, duration: LocationSharingManager.LiveDuration, address: String? = nil, sessionId: String? = nil) -> LocationMessage {
        LocationMessage(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            timestamp: Date(),
            isLive: true,
            expiresAt: Date().addingTimeInterval(duration.seconds),
            accuracy: location.horizontalAccuracy,
            address: address,
            sessionId: sessionId ?? LocationSharingManager.shared.liveShareMessageId,
            isUpdate: false,
            isStopped: false
        )
    }

    // Create live location message with custom duration in seconds (for matching sender's remaining time)
    static func liveWithCustomDuration(from location: CLLocation, durationSeconds: TimeInterval, address: String? = nil, sessionId: String? = nil) -> LocationMessage {
        LocationMessage(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            timestamp: Date(),
            isLive: true,
            expiresAt: Date().addingTimeInterval(durationSeconds),
            accuracy: location.horizontalAccuracy,
            address: address,
            sessionId: sessionId ?? LocationSharingManager.shared.liveShareMessageId,
            isUpdate: false,
            isStopped: false
        )
    }

    // Create live location UPDATE (silent - no notification).
    // 🔧 FIX (2026-06-04): use the share's FIXED end time, not `now + duration`.
    // The previous version recomputed `Date().addingTimeInterval(duration.seconds)`
    // on every 10 s tick, so every update extended the receiver's expiry by the
    // full original duration — the share never expired on the receiver side.
    // Falls back to `now + duration` only if `liveShareEndTime` is nil (shouldn't
    // happen in normal flow because the share is started before any update fires).
    static func liveUpdate(from location: CLLocation, duration: LocationSharingManager.LiveDuration, sessionId: String) -> LocationMessage {
        let expiresAt = LocationSharingManager.shared.liveShareEndTime ?? Date().addingTimeInterval(duration.seconds)
        return LocationMessage(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            timestamp: Date(),
            isLive: true,
            expiresAt: expiresAt,
            accuracy: location.horizontalAccuracy,
            address: nil,
            sessionId: sessionId,
            isUpdate: true,
            isStopped: false
        )
    }

    // Create live location UPDATE with custom duration (for receiver matching sender's time).
    // 🔧 FIX (2026-06-04): take an absolute `expiresAt` directly instead of a
    // duration — the caller computes it ONCE when sharing starts and reuses it,
    // so every update agrees on the SAME end-of-share moment. Without this the
    // receiver's auto-stop hook (asyncAfter in startReceiverLiveSharing) and the
    // bubble countdown both drifted forward indefinitely.
    static func liveUpdateWithExpiry(from location: CLLocation, expiresAt: Date, sessionId: String) -> LocationMessage {
        LocationMessage(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            timestamp: Date(),
            isLive: true,
            expiresAt: expiresAt,
            accuracy: location.horizontalAccuracy,
            address: nil,
            sessionId: sessionId,
            isUpdate: true,
            isStopped: false
        )
    }

    // Compat shim kept so existing callers compile while they migrate.
    // Computes expiresAt = `now + durationSeconds` ONCE-per-call; for stable
    // expiry across ticks switch callers to liveUpdateWithExpiry.
    static func liveUpdateWithCustomDuration(from location: CLLocation, durationSeconds: TimeInterval, sessionId: String) -> LocationMessage {
        liveUpdateWithExpiry(
            from: location,
            expiresAt: Date().addingTimeInterval(durationSeconds),
            sessionId: sessionId
        )
    }
    
    // 📍 Create STOPPED message - sent when sender manually stops sharing
    static func stopped(sessionId: String, lastLocation: CLLocation? = nil) -> LocationMessage {
        let loc = lastLocation ?? LocationSharingManager.shared.currentLocation
        return LocationMessage(
            latitude: loc?.coordinate.latitude ?? 0,
            longitude: loc?.coordinate.longitude ?? 0,
            timestamp: Date(),
            isLive: true,  // Was live
            expiresAt: Date(),  // Expired now
            accuracy: loc?.horizontalAccuracy,
            address: nil,
            sessionId: sessionId,
            isUpdate: true,  // Silent update
            isStopped: true  // 📍 Manually stopped
        )
    }
    
    // MARK: - Custom Decoding for backward compatibility
    
    enum CodingKeys: String, CodingKey {
        case latitude, longitude, timestamp, isLive, expiresAt, accuracy, address, sessionId, isUpdate, isStopped
    }
    
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        latitude = try container.decode(Double.self, forKey: .latitude)
        longitude = try container.decode(Double.self, forKey: .longitude)
        timestamp = try container.decode(Date.self, forKey: .timestamp)
        isLive = try container.decode(Bool.self, forKey: .isLive)
        expiresAt = try container.decodeIfPresent(Date.self, forKey: .expiresAt)
        accuracy = try container.decodeIfPresent(Double.self, forKey: .accuracy)
        address = try container.decodeIfPresent(String.self, forKey: .address)
        sessionId = try container.decodeIfPresent(String.self, forKey: .sessionId)
        isUpdate = try container.decodeIfPresent(Bool.self, forKey: .isUpdate) ?? false
        isStopped = try container.decodeIfPresent(Bool.self, forKey: .isStopped) ?? false  // Default false for old messages
    }
}

// MARK: - Geocoder Helper

class GeocoderHelper {
    static let shared = GeocoderHelper()
    private let geocoder = CLGeocoder()
    
    func getAddress(for location: CLLocation, completion: @escaping (String?) -> Void) {
        geocoder.reverseGeocodeLocation(location) { placemarks, error in
            guard let placemark = placemarks?.first else {
                completion(nil)
                return
            }
            
            var addressParts: [String] = []
            
            if let street = placemark.thoroughfare {
                addressParts.append(street)
            }
            if let city = placemark.locality {
                addressParts.append(city)
            }
            if let country = placemark.country {
                addressParts.append(country)
            }
            
            completion(addressParts.isEmpty ? nil : addressParts.joined(separator: ", "))
        }
    }
}
