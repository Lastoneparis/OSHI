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

    private init() {
        // Synchronous check first (immediate value for init-time decisions)
        checkLocaleFallback()

        // Async: Check App Store storefront (most reliable for App Store region)
        if #available(iOS 16.0, *) {
            Task { @MainActor in
                if let storefront = await Storefront.current {
                    let result = storefront.countryCode == "CHN"
                    if result != self.isChina {
                        self.isChina = result
                        print("🇨🇳 ChinaRegionDetector: Storefront = \(storefront.countryCode) → isChina = \(result)")
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
        isChina = (regionCode == "CN" || regionCode == "CHN")
        print("🇨🇳 ChinaRegionDetector: Locale region = \(regionCode) → isChina = \(isChina)")
    }
}
