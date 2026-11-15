//
//  ChinaRegionDetector.swift
//  OSHI - China Region Detection
//
//  Detects if the user is on the China App Store to disable CallKit
//  (required by MIIT regulation). Uses App Store storefront as primary
//  detection, with device locale as fallback.
//

import Foundation
import StoreKit

class ChinaRegionDetector: ObservableObject {
    static let shared = ChinaRegionDetector()

    /// True if the device is in China (CallKit must be disabled)
    @Published private(set) var isChina: Bool = false

    // Storefront is asynchronous and the locale/time-zone signal can change while the
    // app is open. Keep their evidence separate: recomputing the latter must never
    // clear a mainland storefront result that arrived earlier.
    private var storefrontSaysChina = false
    private var localeObserver: NSObjectProtocol?
    private var timezoneObserver: NSObjectProtocol?

    /// True if CallKit should be bypassed (China regulation OR user preference)
    /// When true: use in-app call UI, custom ringtone, no call history
    var shouldBypassCallKit: Bool {
        return isChina || UserDefaults.standard.bool(forKey: "callKitDisabled")
    }

    /// Reason string for logging
    var bypassReason: String {
        if isChina { return "China regulation" }
        if UserDefaults.standard.bool(forKey: "callKitDisabled") { return "User preference" }
        return "none"
    }

    private init() {
        // Synchronous check first (immediate value for init-time decisions)
        checkLocaleFallback()

        // A traveller can change their system region or time zone without restarting
        // OSHI. These notifications are delivered on the main queue so the published
        // value remains safe for SwiftUI observers.
        localeObserver = NotificationCenter.default.addObserver(
            forName: NSLocale.currentLocaleDidChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.checkLocaleFallback()
        }
        timezoneObserver = NotificationCenter.default.addObserver(
            forName: .NSSystemTimeZoneDidChange,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.checkLocaleFallback()
        }

        // Async: Check App Store storefront (most reliable for App Store region)
        if #available(iOS 16.0, *) {
            Task { @MainActor in
                if let storefront = await Storefront.current {
                    // Storefront can only add China detection, never clear a
                    // locale/time-zone signal — a device physically in China (by
                    // time zone) on a non-CN App Store account must stay in bypass
                    // mode, otherwise its VoIP pushes get dropped.
                    if storefront.countryCode == "CHN" {
                        self.storefrontSaysChina = true
                        self.updateChinaState()
                        OshiLog.ui.info("🇨🇳 ChinaRegionDetector: Storefront = \(storefront.countryCode) → isChina = true")
                    }
                }
            }
        }
    }

    private func checkLocaleFallback() {
        let regionCode: String
        if #available(iOS 16.0, *) {
            regionCode = Locale.current.region?.identifier ?? ""
        } else {
            regionCode = Locale.current.regionCode ?? ""
        }
        let byTimezone = Self.timezoneIsChina()
        let localeSaysChina = regionCode == "CN"
        updateChinaState(localeSaysChina: localeSaysChina, timezoneSaysChina: byTimezone)
        OshiLog.ui.info("🇨🇳 ChinaRegionDetector: Locale region = \(regionCode), tz = \(TimeZone.current.identifier), tzIsChina = \(byTimezone) → isChina = \(isChina)")
    }

    private func updateChinaState(
        localeSaysChina: Bool = false,
        timezoneSaysChina: Bool = false
    ) {
        isChina = storefrontSaysChina || localeSaysChina || timezoneSaysChina
    }

    /// Physically-in-China signal: the device's time zone is a mainland-China
    /// zone. Catches travelers/expats whose App Store account and locale region
    /// aren't CN but who are on a Chinese network — where PushKit VoIP pushes
    /// are dropped, so the app must bypass CallKit and ring via the in-app path.
    /// Hong Kong / Macau are intentionally excluded — VoIP push works there.
    static func timezoneIsChina() -> Bool {
        let mainlandChinaZones: Set<String> = [
            "Asia/Shanghai", "Asia/Urumqi", "Asia/Chongqing",
            "Asia/Harbin", "Asia/Kashgar", "PRC"
        ]
        return mainlandChinaZones.contains(TimeZone.current.identifier)
    }
}
