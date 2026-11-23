//
//  AppReviewManager.swift
//  OSHI
//
//  Manages App Store review prompts
//  Shows after 1 day of usage, then once per day if ignored
//

import Foundation
import UIKit

// MARK: - App Update Checker

class AppUpdateManager: ObservableObject {
    static let shared = AppUpdateManager()

    private let appStoreId = "6753926350"
    private let lastCheckKey = "AppUpdate_LastCheckDate"
    private let dismissedVersionKey = "AppUpdate_DismissedVersion"
    private let checkInterval: TimeInterval = 86400 // 24h between checks

    @Published var updateAvailable = false
    @Published var latestVersion: String?

    private init() {}

    /// Check for updates (throttled to once per day)
    func checkForUpdate() {
        // Throttle: skip if checked less than 24h ago
        if let lastCheck = UserDefaults.standard.object(forKey: lastCheckKey) as? Date,
           Date().timeIntervalSince(lastCheck) < checkInterval {
            return
        }

        Task {
            await performCheck()
        }
    }

    /// Force check (ignores throttle)
    func forceCheck() {
        Task {
            await performCheck()
        }
    }

    private func performCheck() async {
        guard let url = URL(string: "https://itunes.apple.com/lookup?id=\(appStoreId)&country=fr") else { return }

        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let results = json["results"] as? [[String: Any]],
                  let first = results.first,
                  let storeVersion = first["version"] as? String else {
                return
            }

            UserDefaults.standard.set(Date(), forKey: lastCheckKey)

            let currentVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"

            // Compare versions
            if storeVersion.compare(currentVersion, options: .numeric) == .orderedDescending {
                // Skip if user already dismissed this version
                let dismissed = UserDefaults.standard.string(forKey: dismissedVersionKey)
                if dismissed == storeVersion { return }

                await MainActor.run {
                    self.latestVersion = storeVersion
                    self.updateAvailable = true
                }
                OshiLog.ui.info("New version available: \(storeVersion) (current: \(currentVersion))")
            } else {
                OshiLog.ui.info("App is up to date (\(currentVersion))")
            }
        } catch {
            OshiLog.ui.info("Update check failed: \(error.localizedDescription)")
        }
    }

    /// User chose to skip this version
    func dismissUpdate() {
        if let version = latestVersion {
            UserDefaults.standard.set(version, forKey: dismissedVersionKey)
        }
        updateAvailable = false
    }

    /// Open App Store page
    func openAppStore() {
        if let url = URL(string: "https://apps.apple.com/app/id\(appStoreId)") {
            UIApplication.shared.open(url)
        }
        updateAvailable = false
    }
}

/// Le repli « page d'avis » du bouton « Noter OSHI » des réglages, et rien d'autre.
///
/// 🔧 (2026-08-21) Cette classe portait AUSSI un moteur de sollicitation complet
/// — seuils, compteurs, `SKStoreReviewController`, et son propre jeu de clés
/// `AppReview_*` dans UserDefaults. Plus une seule ligne ne l'appelait: c'est
/// `RateAppManager` qui décide quand demander un avis, depuis 2026-08-19.
///
/// Ce n'était pas seulement du code mort. C'était un SECOND système de notation,
/// avec son propre « a déjà noté » et son propre compteur, à côté du registre de
/// tentatives que `RateAppManager` tient sur 365 jours glissants. Deux
/// représentations d'un même fait, dont une que personne ne met à jour — il
/// suffisait qu'on rebranche l'ancienne pour dépenser deux fois le quota d'Apple
/// sans que ni l'une ni l'autre ne le sache.
///
/// Supprimé plutôt que commenté. Ce qui reste est ce qui est appelé.
class AppReviewManager {
    static let shared = AppReviewManager()
    private init() {}

    /// Ouvre directement la fiche App Store sur le formulaire d'avis.
    /// Repli du bouton explicite des réglages, quand les 3 demandes natives de
    /// l'année sont épuisées — voir `RateAppManager.handleRateButton`.
    func openAppStoreReview() {
        if let url = URL(string: "https://apps.apple.com/app/oshi-mesh/id6753926350?action=write-review") {
            UIApplication.shared.open(url)
        }
    }
}
