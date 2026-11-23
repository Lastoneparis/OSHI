//
//  PermissionHelper.swift
//  FIXED: Correct NWListener parameters for Local Network permission
//

import SwiftUI
import CoreBluetooth
import Network
import CoreLocation
@preconcurrency import EventKit

class PermissionHelper: NSObject, ObservableObject {
    @Published var bluetoothAuthorized = false
    @Published var showBluetoothAlert = false
    @Published var showNetworkAlert = false
    @Published var calendarAuthorized = false
    @Published var locationAuthorized = false
    
    private var bluetoothManager: CBCentralManager?
    private var networkMonitor: NWPathMonitor?
    private var localNetworkTrigger = LocalNetworkPermissionTrigger()
    private let eventStore = EKEventStore()
    
    // ✅ Location manager for triggering permission
    private var locationManager: CLLocationManager?
    
    override init() {
        super.init()
        verifyInfoPlist()
    }
    
    // MARK: - Debug: Verify Info.plist
    
    private func verifyInfoPlist() {
        OshiLog.ui.info("\n========================================")
        OshiLog.ui.info("🔍 DEBUG: Verifying Info.plist at Runtime")
        OshiLog.ui.info("========================================")
        
        if let bluetoothAlways = Bundle.main.object(forInfoDictionaryKey: "NSBluetoothAlwaysUsageDescription") as? String {
            OshiLog.ui.info("✅ NSBluetoothAlwaysUsageDescription: \(bluetoothAlways)")
        } else {
            OshiLog.ui.info("❌ MISSING: NSBluetoothAlwaysUsageDescription")
        }
        
        if let bluetoothPeripheral = Bundle.main.object(forInfoDictionaryKey: "NSBluetoothPeripheralUsageDescription") as? String {
            OshiLog.ui.info("✅ NSBluetoothPeripheralUsageDescription: \(bluetoothPeripheral)")
        } else {
            OshiLog.ui.info("⚠️  MISSING: NSBluetoothPeripheralUsageDescription")
        }
        
        if let localNetwork = Bundle.main.object(forInfoDictionaryKey: "NSLocalNetworkUsageDescription") as? String {
            OshiLog.ui.info("✅ NSLocalNetworkUsageDescription: \(localNetwork)")
        } else {
            OshiLog.ui.info("⚠️  MISSING: NSLocalNetworkUsageDescription")
        }
        
        if let camera = Bundle.main.object(forInfoDictionaryKey: "NSCameraUsageDescription") as? String {
            OshiLog.ui.info("✅ NSCameraUsageDescription: \(camera)")
        } else {
            OshiLog.ui.info("⚠️  MISSING: NSCameraUsageDescription")
        }
        
        if let faceID = Bundle.main.object(forInfoDictionaryKey: "NSFaceIDUsageDescription") as? String {
            OshiLog.ui.info("✅ NSFaceIDUsageDescription: \(faceID)")
        } else {
            OshiLog.ui.info("⚠️  MISSING: NSFaceIDUsageDescription")
        }
        
        if let bonjour = Bundle.main.object(forInfoDictionaryKey: "NSBonjourServices") as? [String] {
            OshiLog.ui.info("✅ NSBonjourServices: \(bonjour)")
        } else {
            OshiLog.ui.info("⚠️  MISSING: NSBonjourServices")
        }
        
        if let backgroundModes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] {
            OshiLog.ui.info("✅ UIBackgroundModes: \(backgroundModes)")
        } else {
            OshiLog.ui.info("⚠️  MISSING: UIBackgroundModes")
        }
        
        // ✅ Verify Location keys
        if let locationWhenInUse = Bundle.main.object(forInfoDictionaryKey: "NSLocationWhenInUseUsageDescription") as? String {
            OshiLog.ui.info("✅ NSLocationWhenInUseUsageDescription: \(locationWhenInUse.prefix(50))...")
        } else {
            OshiLog.ui.info("❌ MISSING: NSLocationWhenInUseUsageDescription")
        }
        
        if let locationAlways = Bundle.main.object(forInfoDictionaryKey: "NSLocationAlwaysUsageDescription") as? String {
            OshiLog.ui.info("✅ NSLocationAlwaysUsageDescription: \(locationAlways.prefix(50))...")
        } else {
            OshiLog.ui.info("⚠️  MISSING: NSLocationAlwaysUsageDescription")
        }
        
        if let locationAlwaysAndWhenInUse = Bundle.main.object(forInfoDictionaryKey: "NSLocationAlwaysAndWhenInUseUsageDescription") as? String {
            OshiLog.ui.info("✅ NSLocationAlwaysAndWhenInUseUsageDescription: \(locationAlwaysAndWhenInUse.prefix(50))...")
        } else {
            OshiLog.ui.info("⚠️  MISSING: NSLocationAlwaysAndWhenInUseUsageDescription")
        }
        
        OshiLog.ui.info("========================================\n")
    }
    
    // MARK: - Permission Requests
    
    func requestPermissions() {
        OshiLog.ui.info("\n🔐 Requesting permissions...")
        OshiLog.ui.info("⏰ Current time: \(Date())")
        
        // Request Bluetooth permission
        checkBluetoothPermission()
        
        // ✅ Request Local Network permission
        triggerLocalNetworkPermission()
        
        // Monitor network status
        checkNetworkPermission()
        
        // ✅ Request Calendar permission
        requestCalendarPermission()
        
        // ✅ Request Location permission (like Bluetooth - create manager to trigger)
        requestLocationPermission()
    }
    
    // ✅ NEW: Request Location permission using same pattern as Bluetooth
    func requestLocationPermission() {
        OshiLog.ui.info("\n📍 requestLocationPermission() called")
        
        guard Bundle.main.object(forInfoDictionaryKey: "NSLocationWhenInUseUsageDescription") != nil else {
            OshiLog.ui.info("❌ FATAL: NSLocationWhenInUseUsageDescription not found!")
            return
        }
        
        OshiLog.ui.info("✅ NSLocationWhenInUseUsageDescription verified")
        
        // Delay to show after other dialogs
        DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) { [weak self] in
            guard let self = self else { return }
            
            OshiLog.ui.info("📍 Creating CLLocationManager now...")
            
            // Create location manager (this alone can trigger dialog on some iOS versions)
            self.locationManager = CLLocationManager()
            self.locationManager?.delegate = self
            self.locationManager?.desiredAccuracy = kCLLocationAccuracyBest
            
            let status = self.locationManager?.authorizationStatus ?? .notDetermined
            OshiLog.ui.info("📍 Current location status: \(status.rawValue)")
            
            if status == .notDetermined {
                OshiLog.ui.info("📍 Status is notDetermined - requesting authorization...")
                OshiLog.ui.info("📍 >>> Calling requestWhenInUseAuthorization() <<<")
                self.locationManager?.requestWhenInUseAuthorization()
                
                // Also start updating to force dialog on iPad
                OshiLog.ui.info("📍 >>> Also calling startUpdatingLocation() to force dialog <<<")
                self.locationManager?.startUpdatingLocation()
            } else if status == .authorizedWhenInUse || status == .authorizedAlways {
                OshiLog.ui.info("✅ Location already authorized: \(status.rawValue)")
                self.locationAuthorized = true
            } else {
                OshiLog.ui.info("❌ Location denied/restricted: \(status.rawValue)")
            }
        }
    }
    
    // ✅ Request Calendar permission
    func requestCalendarPermission() {
        OshiLog.ui.info("\n📅 requestCalendarPermission() called")
        
        // Delay to avoid too many dialogs at once
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { [weak self] in
            guard let self = self else { return }
            
            let status = EKEventStore.authorizationStatus(for: .event)
            OshiLog.ui.info("📅 Calendar authorization status: \(status.rawValue)")
            
            if #available(iOS 17.0, *) {
                // iOS 17+: 0=notDetermined, 1=restricted, 2=denied, 3=fullAccess, 4=writeOnly
                switch status.rawValue {
                case 3, 4: // fullAccess or writeOnly
                    self.calendarAuthorized = true
                    OshiLog.ui.info("✅ Calendar already authorized")
                case 0: // notDetermined
                    OshiLog.ui.info("📅 Requesting calendar access (iOS 17+)...")
                    // IMPORTANT: Create a fresh EKEventStore for the request on iOS 17+
                    let freshStore = EKEventStore()
                    Task { @MainActor in
                        do {
                            OshiLog.ui.info("📅 Calling requestFullAccessToEvents()...")
                            let granted = try await freshStore.requestFullAccessToEvents()
                            self.calendarAuthorized = granted
                            OshiLog.ui.info("📅 Calendar fullAccess result: \(granted)")
                        } catch {
                            OshiLog.ui.info("📅 Calendar fullAccess error: \(error.localizedDescription)")
                            // Try write-only as fallback
                            do {
                                OshiLog.ui.info("📅 Trying requestWriteOnlyAccessToEvents()...")
                                let granted = try await freshStore.requestWriteOnlyAccessToEvents()
                                self.calendarAuthorized = granted
                                OshiLog.ui.info("📅 Calendar writeOnly result: \(granted)")
                            } catch {
                                OshiLog.ui.info("📅 Calendar writeOnly also failed: \(error.localizedDescription)")
                            }
                        }
                    }
                default: // denied or restricted
                    self.calendarAuthorized = false
                    OshiLog.ui.info("❌ Calendar access denied (status: \(status.rawValue))")
                }
            } else {
                // iOS 16 and earlier
                switch status {
                case .authorized:
                    self.calendarAuthorized = true
                    OshiLog.ui.info("✅ Calendar already authorized")
                case .notDetermined:
                    OshiLog.ui.info("📅 Requesting calendar access (iOS 16)...")
                    self.eventStore.requestAccess(to: .event) { [weak self] granted, error in
                        DispatchQueue.main.async {
                            self?.calendarAuthorized = granted
                            if let error = error {
                                OshiLog.ui.info("📅 Calendar permission error: \(error.localizedDescription)")
                            }
                            OshiLog.ui.info("📅 Calendar access result: \(granted)")
                        }
                    }
                default:
                    self.calendarAuthorized = false
                    OshiLog.ui.info("❌ Calendar access denied")
                }
            }
        }
    }
    
    // ✅ Trigger Local Network permission dialog
    func triggerLocalNetworkPermission() {
        OshiLog.ui.info("\n📍 triggerLocalNetworkPermission() called")
        
        // Delay to show after Bluetooth dialog
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            self?.localNetworkTrigger.triggerPermission()
        }
    }
    
    func checkBluetoothPermission() {
        OshiLog.ui.info("\n📍 checkBluetoothPermission() called")
        
        guard Bundle.main.object(forInfoDictionaryKey: "NSBluetoothAlwaysUsageDescription") != nil else {
            OshiLog.ui.info("❌ FATAL: NSBluetoothAlwaysUsageDescription not found!")
            return
        }
        
        OshiLog.ui.info("✅ NSBluetoothAlwaysUsageDescription verified, creating CBCentralManager...")
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
            guard let self = self else { return }
            
            OshiLog.ui.info("🔵 Creating CBCentralManager now...")
            
            self.bluetoothManager = CBCentralManager(
                delegate: self,
                queue: nil,
                options: [CBCentralManagerOptionShowPowerAlertKey: true]
            )
            
            OshiLog.ui.info("✅ CBCentralManager created successfully")
            
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
                guard let self = self, let manager = self.bluetoothManager else { return }
                
                OshiLog.ui.info("\n🔍 Manual Bluetooth state check after 1 second:")
                OshiLog.ui.info("   State raw value: \(manager.state.rawValue)")
                
                switch manager.state {
                case .unknown:
                    OshiLog.ui.info("   ⚪️ State: Unknown (system still initializing)")
                    OshiLog.ui.info("   ⏳ State still unknown, will check again in 2 seconds...")
                    
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
                        guard let self = self, let manager = self.bluetoothManager else { return }
                        OshiLog.ui.info("\n🔍 Final Bluetooth state check after 3 seconds total:")
                        OshiLog.ui.info("   State raw value: \(manager.state.rawValue)")
                        
                        if manager.state == .unknown {
                            OshiLog.ui.info("   ⚠️  WARNING: Bluetooth state still unknown after 3 seconds")
                        }
                    }
                case .poweredOn:
                    OshiLog.ui.info("   ✅ State: Powered On (ready to use)")
                case .poweredOff:
                    OshiLog.ui.info("   ⚠️  State: Powered Off")
                case .unauthorized:
                    OshiLog.ui.info("   ❌ State: Unauthorized")
                default:
                    OshiLog.ui.info("   State: \(manager.state)")
                }
            }
        }
    }
    
    func checkNetworkPermission() {
        OshiLog.ui.info("\n📍 checkNetworkPermission() called")
        
        networkMonitor = NWPathMonitor()
        networkMonitor?.pathUpdateHandler = { [weak self] path in
            OshiLog.ui.info("📡 Network path status: \(path.status)")
            
            DispatchQueue.main.async {
                if path.status == .unsatisfied {
                    OshiLog.ui.info("⚠️  Network is unsatisfied")
                    self?.showNetworkAlert = true
                } else {
                    OshiLog.ui.info("✅ Network is available")
                }
            }
        }
        networkMonitor?.start(queue: DispatchQueue.global(qos: .background))
        OshiLog.ui.info("✅ Network monitor started")
    }
    
    func openSettings() {
        if let settingsUrl = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(settingsUrl)
            OshiLog.ui.info("📱 Opening Settings app...")
        }
    }
}

// MARK: - CBCentralManagerDelegate

extension PermissionHelper: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        OshiLog.ui.info("\n📍 centralManagerDidUpdateState DELEGATE CALLED")
        OshiLog.ui.info("🔵 Bluetooth state: \(central.state.rawValue)")
        OshiLog.ui.info("🔵 Thread: \(Thread.isMainThread ? "Main" : "Background")")
        
        DispatchQueue.main.async {
            switch central.state {
            case .unauthorized:
                self.bluetoothAuthorized = false
                self.showBluetoothAlert = true
                OshiLog.ui.info("❌ Bluetooth UNAUTHORIZED")
                
            case .poweredOff:
                self.bluetoothAuthorized = false
                self.showBluetoothAlert = true
                OshiLog.ui.info("⚠️  Bluetooth Powered OFF")
                
            case .poweredOn:
                self.bluetoothAuthorized = true
                self.showBluetoothAlert = false
                OshiLog.ui.info("✅ Bluetooth Powered ON")
                OshiLog.ui.info("✅ Bluetooth is fully functional!")
                OshiLog.ui.info("✅ Ready for mesh networking")
                
            default:
                OshiLog.ui.info("📡 Bluetooth state: \(central.state)")
            }
        }
    }
}

// MARK: - CLLocationManagerDelegate

extension PermissionHelper: CLLocationManagerDelegate {
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        OshiLog.ui.info("\n📍 PermissionHelper: locationManagerDidChangeAuthorization DELEGATE CALLED")
        OshiLog.ui.info("📍 Location status: \(status.rawValue)")
        
        DispatchQueue.main.async {
            switch status {
            case .authorizedWhenInUse:
                self.locationAuthorized = true
                OshiLog.ui.info("✅ Location authorized: When In Use")
                // Stop updates now that we have permission
                self.locationManager?.stopUpdatingLocation()
                // Update the shared LocationSharingManager
                LocationSharingManager.shared.authorizationStatus = status
                
            case .authorizedAlways:
                self.locationAuthorized = true
                OshiLog.ui.info("✅ Location authorized: Always")
                self.locationManager?.stopUpdatingLocation()
                LocationSharingManager.shared.authorizationStatus = status
                
            case .denied:
                self.locationAuthorized = false
                OshiLog.ui.info("❌ Location DENIED")
                self.locationManager?.stopUpdatingLocation()
                LocationSharingManager.shared.authorizationStatus = status
                
            case .restricted:
                self.locationAuthorized = false
                OshiLog.ui.info("❌ Location RESTRICTED")
                self.locationManager?.stopUpdatingLocation()
                LocationSharingManager.shared.authorizationStatus = status
                
            case .notDetermined:
                OshiLog.ui.info("📍 Location not determined yet")
                
            @unknown default:
                OshiLog.ui.info("📍 Location unknown status: \(status.rawValue)")
            }
        }
    }
    
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        // Got a location - permission was granted
        OshiLog.ui.info("📍 PermissionHelper: Got location update - permission granted!")
        locationManager?.stopUpdatingLocation()
    }
    
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        OshiLog.ui.info("📍 PermissionHelper: Location error: \(error.localizedDescription)")
        if let clError = error as? CLError, clError.code == .denied {
            OshiLog.ui.info("📍 Location access was denied")
            locationAuthorized = false
            locationManager?.stopUpdatingLocation()
        }
    }
}

// MARK: - Local Network Permission Trigger

class LocalNetworkPermissionTrigger {
    private var listener: NWListener?
    
    func triggerPermission() {
        OshiLog.ui.info("🔔 Triggering Local Network permission dialog...")
        
        do {
            // ✅ FIXED: Use proper TCP parameters
            let parameters = NWParameters.tcp
            parameters.includePeerToPeer = true
            
            // ✅ Create listener with TCP parameters
            let listener = try NWListener(using: parameters)
            
            // ✅ Set Bonjour service (must match Info.plist!)
            listener.service = NWListener.Service(
                name: "oshi-trigger",
                type: "_ihsotahs._tcp"
            )
            
            self.listener = listener
            
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    OshiLog.ui.info("✅ Listener ready - Local Network dialog should appear!")
                case .failed(let error):
                    OshiLog.ui.info("❌ Listener failed: \(error)")
                case .cancelled:
                    OshiLog.ui.info("🛑 Listener cancelled")
                default:
                    OshiLog.ui.info("📡 Listener state: \(state)")
                }
            }
            
            listener.newConnectionHandler = { connection in
                OshiLog.ui.info("📥 New connection (ignored): \(connection)")
                connection.cancel()
            }
            
            listener.start(queue: .main)
            
            OshiLog.ui.info("✅ Bonjour listener started")
            OshiLog.ui.info("✅ This will trigger Local Network permission dialog!")
            
            // Stop after 3 seconds (gives iOS time to show dialog)
            DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) { [weak self] in
                self?.listener?.cancel()
                OshiLog.ui.info("🛑 Permission trigger stopped")
            }
            
        } catch {
            OshiLog.ui.info("❌ Failed to create listener: \(error)")
        }
    }
}
