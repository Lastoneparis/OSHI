//
//  LocationPermissionRequestView.swift
//  OSHI
//
//  Handles location permission requests including re-requests after denial
//  or app updates. Shows appropriate UI based on permission state.
//

import SwiftUI
import CoreLocation

// MARK: - Brand Color
private let appBrandColor = Theme.brand

// MARK: - Layout
/// Cap content width on wide screens (iPad / landscape), centered.
private let permissionContentMaxWidth: CGFloat = 480

// MARK: - Fresh Location Permission Requester (WhatsApp-style)
// Uses a singleton pattern to ensure the CLLocationManager stays alive

class FreshLocationPermissionRequester: NSObject, CLLocationManagerDelegate {
    static let shared = FreshLocationPermissionRequester()
    
    private var locationManager: CLLocationManager?
    private var completion: ((CLAuthorizationStatus) -> Void)?
    private var timeoutTimer: Timer?
    private var hasReceivedAuthCallback = false
    private var isRequestInProgress = false
    
    private override init() {
        super.init()
    }
    
    func requestPermission(completion: @escaping (CLAuthorizationStatus) -> Void) {
        // Prevent duplicate requests
        guard !isRequestInProgress else {
            OshiLog.maps.info("📍 [FRESH] Request already in progress, ignoring")
            return
        }
        
        self.completion = completion
        self.isRequestInProgress = true
        
        // Check Location Services on background thread to avoid UI blocking
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let locationServicesEnabled = CLLocationManager.locationServicesEnabled()
            OshiLog.maps.info("📍 [FRESH] Location Services enabled globally: \(locationServicesEnabled)")
            
            DispatchQueue.main.async {
                guard let self = self else { return }
                
                if !locationServicesEnabled {
                    OshiLog.maps.info("📍 [FRESH] ⚠️ Location Services is DISABLED on this device!")
                    OshiLog.maps.info("📍 [FRESH] User must enable: Settings > Privacy & Security > Location Services")
                    self.isRequestInProgress = false
                    completion(.denied)
                    return
                }
                
                self.hasReceivedAuthCallback = false
                
                // Clean up any existing manager
                self.cleanup()
                
                // Create a FRESH location manager each time
                self.locationManager = CLLocationManager()
                self.locationManager?.delegate = self
                self.locationManager?.desiredAccuracy = kCLLocationAccuracyBest
                
                let currentStatus = self.locationManager?.authorizationStatus ?? .notDetermined
                OshiLog.maps.info("📍 [FRESH] Current status before request: \(currentStatus.rawValue)")
                
                if currentStatus == .notDetermined {
                    OshiLog.maps.info("📍 [FRESH] Requesting authorization with fresh manager...")
                    OshiLog.maps.info("📍 [FRESH] iOS permission dialog should appear NOW...")
                    
                    // WHATSAPP-STYLE: Request "When In Use" first
                    // iOS will show: "Allow Once" / "Allow While Using" / "Don't Allow"
                    self.locationManager?.requestWhenInUseAuthorization()
                    
                    // Set timeout - if no response in 15 seconds, assume dialog didn't appear
                    self.timeoutTimer = Timer.scheduledTimer(withTimeInterval: 15.0, repeats: false) { [weak self] _ in
                        guard let self = self, self.isRequestInProgress else { return }
                        OshiLog.maps.info("📍 [FRESH] Timeout - checking final status")
                        let status = self.locationManager?.authorizationStatus ?? .notDetermined
                        if status == .notDetermined {
                            OshiLog.maps.info("📍 [FRESH] ⚠️ Still notDetermined after timeout - dialog may not have appeared")
                            OshiLog.maps.info("📍 [FRESH] This can happen if the app was backgrounded or dialog was dismissed")
                        }
                        self.finishRequest(with: status)
                    }
                } else if currentStatus == .authorizedWhenInUse {
                    // Already have "When In Use" - request upgrade to "Always" to get 3 options in Settings
                    OshiLog.maps.info("📍 [FRESH] Already have WhenInUse - requesting Always for full options...")
                    self.locationManager?.requestAlwaysAuthorization()
                    
                    // Give iOS time to process
                    self.timeoutTimer = Timer.scheduledTimer(withTimeInterval: 3.0, repeats: false) { [weak self] _ in
                        guard let self = self, self.isRequestInProgress else { return }
                        let status = self.locationManager?.authorizationStatus ?? currentStatus
                        self.finishRequest(with: status)
                    }
                } else {
                    // Already determined (denied, restricted, or always) - return current status
                    OshiLog.maps.info("📍 [FRESH] Already determined: \(currentStatus.rawValue)")
                    self.finishRequest(with: currentStatus)
                }
            }
        }
    }
    
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        OshiLog.maps.info("📍 [FRESH] Authorization changed to: \(status.rawValue)")
        
        // Skip the initial callback that fires with .notDetermined
        guard status != .notDetermined else {
            OshiLog.maps.info("📍 [FRESH] Ignoring initial notDetermined callback")
            return
        }
        
        // User made a choice
        hasReceivedAuthCallback = true
        
        // WHATSAPP-STYLE: If user granted "When In Use", request "Always" to get 3 options in Settings
        if status == .authorizedWhenInUse {
            OshiLog.maps.info("📍 [FRESH] User granted WhenInUse - now requesting Always for 3 Settings options...")
            // Request Always authorization - this will either:
            // 1. Show a prompt asking to upgrade to "Always"
            // 2. Or just add the "Always" option to Settings
            manager.requestAlwaysAuthorization()
            
            // Wait a moment for iOS to process, then finish
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
                let finalStatus = manager.authorizationStatus
                OshiLog.maps.info("📍 [FRESH] Final status after Always request: \(finalStatus.rawValue)")
                self?.finishRequest(with: finalStatus)
            }
        } else {
            // Denied, restricted, or already Always - finish immediately
            finishRequest(with: status)
        }
    }
    
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        OshiLog.maps.info("📍 [FRESH] Got location - permission granted!")
    }
    
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        OshiLog.maps.info("📍 [FRESH] Location error: \(error.localizedDescription)")
        if let clError = error as? CLError {
            OshiLog.maps.info("📍 [FRESH] CLError code: \(clError.code.rawValue)")
            // IMPORTANT: Do NOT treat location errors as permission denials!
            // During permission request flow, we only care about authorization callbacks.
            // Location errors can happen for many reasons (no GPS signal, timeout, etc.)
            // and should not affect the permission flow.
            OshiLog.maps.info("📍 [FRESH] Ignoring location error during permission request (this is normal)")
        }
    }
    
    private func finishRequest(with status: CLAuthorizationStatus) {
        guard isRequestInProgress else { return }
        
        timeoutTimer?.invalidate()
        timeoutTimer = nil
        isRequestInProgress = false
        
        OshiLog.maps.info("📍 [FRESH] Finishing request with status: \(status.rawValue)")
        
        // Call completion on main thread
        DispatchQueue.main.async { [weak self] in
            self?.completion?(status)
            self?.completion = nil
        }
        
        // Don't cleanup manager immediately - keep it alive for a bit
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
            self?.cleanup()
        }
    }
    
    private func cleanup() {
        locationManager?.stopUpdatingLocation()
        locationManager?.delegate = nil
        locationManager = nil
    }
}

// MARK: - Permission State Manager

class LocationPermissionStateManager: ObservableObject {
    static let shared = LocationPermissionStateManager()
    
    private let hasRequestedPermissionKey = "oshi_location_permission_requested"
    private let lastAppVersionKey = "oshi_last_app_version_location"
    private let permissionDeniedCountKey = "oshi_location_denied_count"
    
    @Published var shouldShowPermissionRequest = false
    
    init() {
        checkIfShouldShowRequest()
    }
    
    // Check if we need to show permission request
    func checkIfShouldShowRequest() {
        // Use the shared LocationSharingManager to avoid creating new instances
        let status = LocationSharingManager.shared.authorizationStatus
        
        // If already authorized, no need to show
        if status == .authorizedWhenInUse || status == .authorizedAlways {
            shouldShowPermissionRequest = false
            return
        }
        
        // If not determined, we can request normally
        if status == .notDetermined {
            shouldShowPermissionRequest = true
            return
        }
        
        // If denied/restricted, check if app was updated
        if status == .denied || status == .restricted {
            if wasAppUpdated() {
                shouldShowPermissionRequest = true
            }
        }
    }
    
    // Check if app was updated since last permission request
    private func wasAppUpdated() -> Bool {
        let currentVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let lastVersion = UserDefaults.standard.string(forKey: lastAppVersionKey) ?? ""
        
        return currentVersion != lastVersion
    }
    
    // Mark that we requested permission for current version
    func markPermissionRequested() {
        let currentVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        UserDefaults.standard.set(true, forKey: hasRequestedPermissionKey)
        UserDefaults.standard.set(currentVersion, forKey: lastAppVersionKey)
    }
    
    // Track denial count
    func incrementDenialCount() {
        let count = UserDefaults.standard.integer(forKey: permissionDeniedCountKey)
        UserDefaults.standard.set(count + 1, forKey: permissionDeniedCountKey)
    }
    
    var denialCount: Int {
        UserDefaults.standard.integer(forKey: permissionDeniedCountKey)
    }
    
    // Reset on successful grant
    func resetOnGrant() {
        UserDefaults.standard.set(0, forKey: permissionDeniedCountKey)
        markPermissionRequested()
    }
}

// MARK: - Location Permission Request View

struct LocationPermissionRequestView: View {
    @Environment(\.dismiss) var dismiss
    
    // Use the existing singleton - this is properly retained
    @ObservedObject private var locationManager = LocationSharingManager.shared
    @StateObject private var permissionState = LocationPermissionStateManager.shared
    
    @State private var showingWhyNeeded = false
    @State private var hasAttemptedRequest = false
    @State private var isRequestingPermission = false
    
    let onPermissionGranted: () -> Void
    let onDismiss: () -> Void
    
    var body: some View {
        VStack(spacing: 24) {
            // Close button
            HStack {
                Spacer()
                Button {
                    onDismiss()
                    dismiss()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title2)
                        .foregroundColor(.secondary)
                }
                .accessibilityLabel("common.close".localized)
            }
            .padding(.horizontal)

            Spacer()

            // Icon (decorative)
            ZStack {
                Circle()
                    .fill(appBrandColor.opacity(0.15))
                    .frame(width: 120, height: 120)

                Circle()
                    .fill(appBrandColor.opacity(0.3))
                    .frame(width: 90, height: 90)

                Image(systemName: "location.fill")
                    .font(.scaled(40))
                    .foregroundColor(appBrandColor)
            }
            .accessibilityHidden(true)

            // Title & Description
            VStack(spacing: 12) {
                Text(titleText)
                    .font(.title2)
                    .fontWeight(.bold)
                    .multilineTextAlignment(.center)

                Text(descriptionText)
                    .font(.body)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 20)
            }
            
            // Why needed expandable
            Button {
                withAnimation {
                    showingWhyNeeded.toggle()
                }
            } label: {
                HStack {
                    Text("location.why_needed".localized)
                        .font(.subheadline)
                        .foregroundColor(appBrandColor)
                    Image(systemName: showingWhyNeeded ? "chevron.up" : "chevron.down")
                        .font(.caption)
                        .foregroundColor(appBrandColor)
                        .accessibilityHidden(true)
                }
            }
            .accessibilityLabel("location.why_needed".localized)
            .accessibilityAddTraits(showingWhyNeeded ? .isSelected : [])
            
            if showingWhyNeeded {
                Text("location.why_needed_explanation".localized)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 30)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
            
            Spacer()
            
            // Action Buttons
            VStack(spacing: 12) {
                // Primary button
                Button {
                    #if !targetEnvironment(macCatalyst)
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    #endif
                    handlePrimaryAction()
                } label: {
                    HStack {
                        if isRequestingPermission {
                            ProgressView()
                                .progressViewStyle(CircularProgressViewStyle(tint: .white))
                        } else {
                            Image(systemName: primaryButtonIcon)
                                .accessibilityHidden(true)
                        }
                        Text(primaryButtonText)
                            .fontWeight(.semibold)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(appBrandColor)
                    .foregroundColor(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .disabled(isRequestingPermission)
                .accessibilityLabel(primaryButtonText)
                
                // Secondary button (Not Now)
                Button {
                    onDismiss()
                    dismiss()
                } label: {
                    Text("location.not_now".localized)
                        .font(.body)
                        .foregroundColor(.secondary)
                        .padding(.vertical, 12)
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 30)
        }
        .frame(maxWidth: permissionContentMaxWidth)
        .frame(maxWidth: .infinity)
        .background(Color(.systemBackground))
        .onAppear {
            OshiLog.maps.info("📍 LocationPermissionRequestView appeared")
            OshiLog.maps.info("📍 Current status: \(locationManager.authorizationStatus.rawValue)")
            OshiLog.maps.info("📍 isAuthorized: \(locationManager.isAuthorized)")
            OshiLog.maps.info("📍 needsPermission: \(locationManager.needsPermission)")
            OshiLog.maps.info("📍 isDenied: \(locationManager.isDenied)")
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
            // Check permission when returning from Settings
            checkPermissionAfterReturn()
        }
        .onChange(of: locationManager.authorizationStatus) { _, newStatus in
            handleAuthorizationChange(newStatus)
        }
    }
    
    // MARK: - Computed Properties
    
    private var isDenied: Bool {
        locationManager.isDenied
    }
    
    private var titleText: String {
        if isDenied {
            return "location.permission_denied".localized
        }
        return "location.permission_required".localized
    }
    
    private var descriptionText: String {
        if isDenied || hasAttemptedRequest {
            // After attempt or denial, show specific instructions
            return "location.permission_denied_message".localized
        }
        return "location.permission_message".localized
    }
    
    private var primaryButtonText: String {
        if isDenied || hasAttemptedRequest {
            return "location.open_settings".localized
        }
        return "location.enable_location".localized
    }
    
    private var primaryButtonIcon: String {
        if isDenied || hasAttemptedRequest {
            return "gear"
        }
        return "location.fill"
    }
    
    // MARK: - Actions
    
    private func handlePrimaryAction() {
        OshiLog.maps.info("📍 ════════════════════════════════════════════")
        OshiLog.maps.info("📍 PRIMARY ACTION TAPPED (WhatsApp-style)")
        OshiLog.maps.info("📍 ════════════════════════════════════════════")
        OshiLog.maps.info("📍 isDenied: \(isDenied)")
        OshiLog.maps.info("📍 hasAttemptedRequest: \(hasAttemptedRequest)")
        
        // Check current status using a fresh check
        let currentStatus = CLLocationManager().authorizationStatus
        OshiLog.maps.info("📍 Fresh status check: \(currentStatus.rawValue)")
        
        // If already authorized, just dismiss
        if currentStatus == .authorizedWhenInUse || currentStatus == .authorizedAlways {
            OshiLog.maps.info("📍 ✅ Already authorized!")
            permissionState.resetOnGrant()
            LocationSharingManager.shared.authorizationStatus = currentStatus
            onPermissionGranted()
            dismiss()
            return
        }
        
        // If denied or restricted, must go to Settings
        if currentStatus == .denied || currentStatus == .restricted {
            OshiLog.maps.info("📍 Permission denied/restricted - opening Settings")
            OshiLog.maps.info("📍 User must manually enable: Settings > Privacy & Security > Location Services > OSHI")
            openAppSettings()
            return
        }
        
        // If already attempted before in this session, go to Settings
        if hasAttemptedRequest {
            OshiLog.maps.info("📍 Already attempted - opening Settings")
            openAppSettings()
            return
        }
        
        // First attempt - use fresh requester (WhatsApp-style)
        hasAttemptedRequest = true
        isRequestingPermission = true
        
        OshiLog.maps.info("📍 ═══════════════════════════════════════")
        OshiLog.maps.info("📍 REQUESTING PERMISSION (WhatsApp-style)")
        OshiLog.maps.info("📍 ═══════════════════════════════════════")
        OshiLog.maps.info("📍 Using singleton CLLocationManager...")
        OshiLog.maps.info("📍 If iOS dialog doesn't appear, Settings will open.")
        
        // Use singleton requester (properly retained)
        FreshLocationPermissionRequester.shared.requestPermission { [self] status in
            DispatchQueue.main.async {
                isRequestingPermission = false
                
                OshiLog.maps.info("📍 Permission result: \(status.rawValue)")
                
                if status == .authorizedWhenInUse || status == .authorizedAlways {
                    OshiLog.maps.info("📍 ✅ Permission GRANTED!")
                    permissionState.resetOnGrant()
                    LocationSharingManager.shared.authorizationStatus = status
                    onPermissionGranted()
                    dismiss()
                } else if status == .denied {
                    OshiLog.maps.info("📍 ❌ Permission DENIED")
                    LocationSharingManager.shared.authorizationStatus = status
                    // Stay on screen - user can tap again to go to Settings
                } else {
                    // Still notDetermined - dialog probably didn't appear
                    OshiLog.maps.info("📍 ⚠️ Dialog may not have appeared - opening Settings")
                    openAppSettings()
                }
            }
        }
        
        permissionState.markPermissionRequested()
    }
    
    private func openAppSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }
    
    private func checkPermissionAfterReturn() {
        // Force refresh the status from the system
        OshiLog.maps.info("📍 Returning from Settings, checking permission...")
        
        // Use forceRefreshAuthorization which is more robust
        locationManager.forceRefreshAuthorization()
        
        // Check multiple times with delays (iOS can be slow to update)
        let delays = [0.3, 0.7, 1.2, 2.0, 3.0]
        for (index, delay) in delays.enumerated() {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [self] in
                locationManager.forceRefreshAuthorization()
                let currentStatus = locationManager.authorizationStatus
                OshiLog.maps.info("📍 Checking permission after \(delay)s (check \(index + 1)/\(delays.count)): authorized=\(currentStatus == .authorizedWhenInUse || currentStatus == .authorizedAlways)")
                
                if currentStatus == .authorizedWhenInUse || currentStatus == .authorizedAlways {
                    OshiLog.maps.info("📍 Permission now granted!")
                    permissionState.resetOnGrant()
                    onPermissionGranted()
                    dismiss()
                } else if index == delays.count - 1 {
                    // Last check - inform user if still not granted
                    OshiLog.maps.info("📍 Permission still not granted after all checks")
                }
            }
        }
    }
    
    private func handleAuthorizationChange(_ status: CLAuthorizationStatus) {
        OshiLog.maps.info("📍 Authorization changed in view: \(status.rawValue)")
        isRequestingPermission = false
        
        switch status {
        case .authorizedWhenInUse, .authorizedAlways:
            OshiLog.maps.info("📍 Permission granted! Dismissing...")
            permissionState.resetOnGrant()
            onPermissionGranted()
            dismiss()
        case .denied, .restricted:
            OshiLog.maps.info("📍 Permission denied")
            if hasAttemptedRequest {
                permissionState.incrementDenialCount()
            }
        default:
            break
        }
    }
}

// MARK: - Compact Permission Request (Alert Style)

struct LocationPermissionAlertView: View {
    @Binding var isPresented: Bool
    @StateObject private var locationManager = LocationSharingManager.shared
    
    let onPermissionGranted: () -> Void
    
    var body: some View {
        VStack(spacing: 20) {
            // Header
            HStack {
                Image(systemName: "location.fill")
                    .font(.title2)
                    .foregroundColor(appBrandColor)
                    .accessibilityHidden(true)

                Text("location.permission_required".localized)
                    .font(.headline)

                Spacer()
            }
            
            // Message
            Text(messageText)
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.leading)
            
            // Buttons
            HStack(spacing: 12) {
                // Cancel
                Button {
                    isPresented = false
                } label: {
                    Text("location.not_now".localized)
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Color(.systemGray5))
                        .foregroundColor(.primary)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                }
                
                // Action
                Button {
                    #if !targetEnvironment(macCatalyst)
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    #endif
                    handleAction()
                } label: {
                    Text(actionButtonText)
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(appBrandColor)
                        .foregroundColor(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                }
            }
        }
        .padding(20)
        .frame(maxWidth: permissionContentMaxWidth)
        .background(Color(.systemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .shadow(color: .black.opacity(0.2), radius: 20, x: 0, y: 10)
        .padding(.horizontal, 30)
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
            checkPermissionAfterReturn()
        }
        .onChange(of: locationManager.authorizationStatus) { _, newStatus in
            if newStatus == .authorizedWhenInUse || newStatus == .authorizedAlways {
                onPermissionGranted()
                isPresented = false
            }
        }
    }
    
    private var isDenied: Bool {
        locationManager.authorizationStatus == .denied || locationManager.authorizationStatus == .restricted
    }
    
    private var messageText: String {
        if isDenied {
            return "location.settings_instruction".localized
        }
        return "location.permission_message".localized
    }
    
    private var actionButtonText: String {
        if isDenied {
            return "location.go_to_settings".localized
        }
        return "location.request_again".localized
    }
    
    private func handleAction() {
        if isDenied {
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url)
            }
        } else {
            locationManager.requestPermission()
        }
    }
    
    private func checkPermissionAfterReturn() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            if locationManager.isAuthorized {
                onPermissionGranted()
                isPresented = false
            }
        }
    }
}

// MARK: - View Modifier for Easy Integration

struct LocationPermissionModifier: ViewModifier {
    @Binding var isPresented: Bool
    @StateObject private var locationManager = LocationSharingManager.shared
    
    let onPermissionGranted: () -> Void
    let style: PermissionRequestStyle
    
    enum PermissionRequestStyle {
        case fullScreen
        case sheet
        case alert
    }
    
    func body(content: Content) -> some View {
        content
            .sheet(isPresented: $isPresented) {
                if style == .fullScreen || style == .sheet {
                    LocationPermissionRequestView(
                        onPermissionGranted: onPermissionGranted,
                        onDismiss: { isPresented = false }
                    )
                    .presentationDetents(style == .sheet ? [.medium, .large] : [.large])
                }
            }
            .overlay {
                if style == .alert && isPresented {
                    ZStack {
                        Color.black.opacity(0.4)
                            .ignoresSafeArea()
                            .onTapGesture {
                                isPresented = false
                            }
                        
                        LocationPermissionAlertView(
                            isPresented: $isPresented,
                            onPermissionGranted: onPermissionGranted
                        )
                    }
                    .transition(.opacity)
                }
            }
    }
}

extension View {
    func locationPermissionRequest(
        isPresented: Binding<Bool>,
        style: LocationPermissionModifier.PermissionRequestStyle = .sheet,
        onPermissionGranted: @escaping () -> Void
    ) -> some View {
        modifier(LocationPermissionModifier(
            isPresented: isPresented,
            onPermissionGranted: onPermissionGranted,
            style: style
        ))
    }
}

// MARK: - Helper Function to Check and Request

extension LocationSharingManager {
    /// Call this when user taps on location share button
    /// Returns true if permission is granted, false if request sheet should be shown
    func checkAndRequestPermission(completion: @escaping (Bool, Bool) -> Void) {
        // (isGranted, shouldShowRequest)
        
        switch authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            completion(true, false)
            
        case .notDetermined:
            // Can request directly
            completion(false, true)
            
        case .denied, .restricted:
            // Need to show sheet to guide to settings
            completion(false, true)
            
        @unknown default:
            completion(false, true)
        }
    }
}

// MARK: - Preview

#Preview("Permission Request - Full") {
    LocationPermissionRequestView(
        onPermissionGranted: { OshiLog.maps.info("Granted!") },
        onDismiss: { OshiLog.maps.info("Dismissed") }
    )
}

#Preview("Permission Alert") {
    ZStack {
        Color.gray.opacity(0.3).ignoresSafeArea()
        
        LocationPermissionAlertView(
            isPresented: .constant(true),
            onPermissionGranted: { OshiLog.maps.info("Granted!") }
        )
    }
}
