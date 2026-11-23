//
//  StoreUpdateCheck.swift
//  OSHI — « Update available »: the App Store has a newer version than this one.
//
//  WHAT IT DOES
//  Asks the public iTunes lookup endpoint which version of OSHI is LIVE in the
//  device's storefront, and if it is strictly newer than this build, offers a
//  dismissible popup with "Update" (App Store product page) and "Later" (this
//  version is snoozed for 3 days).
//
//  HOW IT RELATES TO THE TWO EXISTING LEVERS
//  - `V2FeatureFlag.updateRequired` → `UpdateRequiredView`: server-driven wall,
//    the whole UI is replaced. Untouched.
//  - `MinimumVersionGate`: server-driven soft banner when this build is under
//    the project's support floor. Untouched; when its banner is up, this popup
//    stays away so the user never sees two update asks at once.
//  - This file: store-driven, purely informative. It knows nothing about
//    protocol floors — only "a newer build is in the store".
//
//  MAC
//  The Mac app is Mac Catalyst of the SAME target, same bundle id, same App
//  Store record (app id 6753926350, universal purchase) and the same
//  MARKETING_VERSION. So the same code runs on the Mac; only the store link
//  differs (`macappstore://`). The lookup has ONE `version` per record: it is
//  the iOS one. If a Mac release ever lags behind the iOS one, a Mac user can be
//  told about a version the Mac App Store does not have yet — "Later" then
//  snoozes it, and it can never fire for a version EQUAL to or older than the
//  installed one.
//
//  APP REVIEW
//  A build under review is NEWER than the live one. The decision is strictly
//  "live > installed": equal or lower never prompts, and an unreadable version
//  on either side never prompts.
//
//  PRIVACY / FAILURE
//  The request carries the bundle id and a two-letter country, nothing else: no
//  identity, no cookies, an ephemeral session, no cache. At most one network
//  call per launch and at most one every 12 h (the last answer is cached). Every
//  failure — offline, timeout, non-200, malformed body, app not listed in this
//  storefront — is silent and produces "nothing to show".
//

import Foundation
import SwiftUI
import StoreKit
#if canImport(UIKit)
import UIKit
#endif
#if canImport(CallKit)
import CallKit
#endif

// MARK: - Pure logic

enum StoreUpdateCheck {

    /// App Store record of OSHI (iOS + Mac Catalyst, universal purchase).
    static let appStoreID = 6753926350
    static let fallbackBundleID = "Moriceau-lab.Genesis"

    /// Network at most this often.
    static let checkInterval: TimeInterval = 12 * 60 * 60
    /// "Later" hides THIS version for this long. A newer version shows at once.
    static let snoozeInterval: TimeInterval = 3 * 24 * 60 * 60

    enum Lookup: Equatable {
        /// The record was found and carries a readable version.
        case live(String)
        /// Valid answer, but OSHI is not sold in this storefront (`results: []`).
        case notListed
        /// Not JSON, wrong shape, wrong app, unreadable version.
        case unusable
    }

    /// `https://itunes.apple.com/lookup?bundleId=…&country=xx`. Nothing else.
    static func lookupURL(bundleID: String, country: String?) -> URL? {
        var components = URLComponents(string: "https://itunes.apple.com/lookup")
        var items = [URLQueryItem(name: "bundleId", value: bundleID)]
        if let country, let normalized = normalizedCountry(country) {
            items.append(URLQueryItem(name: "country", value: normalized))
        }
        components?.queryItems = items
        return components?.url
    }

    /// Reads the lookup body without ever throwing.
    ///
    /// When `expectedTrackID` is given, a result for another app (a wrong or
    /// recycled bundle id) is `.unusable`, never a version to compare to.
    static func parse(_ data: Data, expectedTrackID: Int? = appStoreID) -> Lookup {
        struct Body: Decodable {
            struct Result: Decodable {
                let version: String?
                let trackId: Int?
            }
            let resultCount: Int?
            let results: [Result]?
        }
        guard let body = try? JSONDecoder().decode(Body.self, from: data),
              let results = body.results else { return .unusable }
        guard !results.isEmpty else { return .notListed }
        let candidates = results.filter { result in
            guard let expectedTrackID else { return true }
            return result.trackId == expectedTrackID
        }
        guard let first = candidates.first,
              let raw = first.version?.trimmingCharacters(in: .whitespacesAndNewlines),
              AppVersion.components(raw) != nil else { return .unusable }
        return .live(raw)
    }

    /// The version to announce, or `nil`. Strictly newer only: equal (we ARE
    /// the live build) and lower (a review / TestFlight build) never prompt.
    static func versionToAnnounce(installed: String, live: String?) -> String? {
        guard let live, let order = AppVersion.compare(installed, live) else { return nil }
        return order == .orderedAscending ? live : nil
    }

    /// Two ASCII letters, lowercased; anything else (`"419"`, `"FRA"`, `""`) → nil.
    static func normalizedCountry(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count == 2, trimmed.allSatisfy({ $0.isASCII && $0.isLetter }) else { return nil }
        return trimmed.lowercased()
    }

    /// The storefront wins (it is the store the user actually buys from); the
    /// device region is the fallback. StoreKit gives ISO 3166-1 alpha-3, the
    /// lookup wants alpha-2.
    static func country(storefrontAlpha3: String?, localeRegion: String?) -> String? {
        if let s = storefrontAlpha3?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(),
           let alpha2 = alpha3ToAlpha2[s] {
            return alpha2.lowercased()
        }
        if let region = localeRegion { return normalizedCountry(region) }
        return nil
    }

    /// Is the network due? Once per launch is enforced by the caller; this is
    /// the 12 h part. A last-check date in the future (clock moved back) is due.
    static func isCheckDue(lastCheck: Date?, now: Date, interval: TimeInterval = checkInterval) -> Bool {
        guard let lastCheck else { return true }
        let elapsed = now.timeIntervalSince(lastCheck)
        return elapsed < 0 || elapsed >= interval
    }

    /// Where the popup may NOT appear. Every flag is an independent veto.
    struct Context: Equatable {
        var mainUIVisible: Bool = true          // false: splash, onboarding, lock screen
        var callActive: Bool = false
        var otherUIPresented: Bool = false      // sheet, cover, alert, call UI, rating sheet
        var supportBannerShowing: Bool = false  // MinimumVersionGate's own ask
    }

    static func mayPresent(_ c: Context) -> Bool {
        c.mainUIVisible && !c.callActive && !c.otherUIPresented && !c.supportBannerShowing
    }

    /// App Store page: in-app store app on both platforms, web page as fallback.
    static var storeURL: URL {
        #if targetEnvironment(macCatalyst)
        return URL(string: "macappstore://apps.apple.com/app/id\(appStoreID)")!
        #else
        return URL(string: "itms-apps://apps.apple.com/app/id\(appStoreID)")!
        #endif
    }
    static let webStoreURL = URL(string: "https://apps.apple.com/app/oshi-mesh/id6753926350")!

    /// ISO 3166-1 alpha-3 → alpha-2 for App Store storefronts (and their
    /// neighbours). An unknown code falls back to the device region.
    static let alpha3ToAlpha2: [String: String] = [
        "AFG": "AF", "ALB": "AL", "DZA": "DZ", "AGO": "AO", "AIA": "AI", "ATG": "AG", "ARG": "AR",
        "ARM": "AM", "AUS": "AU", "AUT": "AT", "AZE": "AZ", "BHS": "BS", "BHR": "BH", "BGD": "BD",
        "BRB": "BB", "BLR": "BY", "BEL": "BE", "BLZ": "BZ", "BEN": "BJ", "BMU": "BM", "BTN": "BT",
        "BOL": "BO", "BIH": "BA", "BWA": "BW", "BRA": "BR", "VGB": "VG", "BRN": "BN", "BGR": "BG",
        "BFA": "BF", "KHM": "KH", "CMR": "CM", "CAN": "CA", "CPV": "CV", "CYM": "KY", "TCD": "TD",
        "CHL": "CL", "CHN": "CN", "COL": "CO", "COD": "CD", "COG": "CG", "CRI": "CR", "CIV": "CI",
        "HRV": "HR", "CYP": "CY", "CZE": "CZ", "DNK": "DK", "DMA": "DM", "DOM": "DO", "ECU": "EC",
        "EGY": "EG", "SLV": "SV", "EST": "EE", "SWZ": "SZ", "FJI": "FJ", "FIN": "FI", "FRA": "FR",
        "GAB": "GA", "GMB": "GM", "GEO": "GE", "DEU": "DE", "GHA": "GH", "GRC": "GR", "GRD": "GD",
        "GTM": "GT", "GIN": "GN", "GNB": "GW", "GUY": "GY", "HND": "HN", "HKG": "HK", "HUN": "HU",
        "ISL": "IS", "IND": "IN", "IDN": "ID", "IRQ": "IQ", "IRL": "IE", "ISR": "IL", "ITA": "IT",
        "JAM": "JM", "JPN": "JP", "JOR": "JO", "KAZ": "KZ", "KEN": "KE", "KOR": "KR", "XKX": "XK",
        "KWT": "KW", "KGZ": "KG", "LAO": "LA", "LVA": "LV", "LBN": "LB", "LBR": "LR", "LBY": "LY",
        "LTU": "LT", "LUX": "LU", "MAC": "MO", "MDG": "MG", "MWI": "MW", "MYS": "MY", "MDV": "MV",
        "MLI": "ML", "MLT": "MT", "MRT": "MR", "MUS": "MU", "MEX": "MX", "FSM": "FM", "MDA": "MD",
        "MNG": "MN", "MNE": "ME", "MSR": "MS", "MAR": "MA", "MOZ": "MZ", "MMR": "MM", "NAM": "NA",
        "NRU": "NR", "NPL": "NP", "NLD": "NL", "NZL": "NZ", "NIC": "NI", "NER": "NE", "NGA": "NG",
        "MKD": "MK", "NOR": "NO", "OMN": "OM", "PAK": "PK", "PLW": "PW", "PAN": "PA", "PNG": "PG",
        "PRY": "PY", "PER": "PE", "PHL": "PH", "POL": "PL", "PRT": "PT", "QAT": "QA", "ROU": "RO",
        "RUS": "RU", "RWA": "RW", "KNA": "KN", "LCA": "LC", "VCT": "VC", "WSM": "WS", "STP": "ST",
        "SAU": "SA", "SEN": "SN", "SRB": "RS", "SYC": "SC", "SLE": "SL", "SGP": "SG", "SVK": "SK",
        "SVN": "SI", "SLB": "SB", "ZAF": "ZA", "ESP": "ES", "LKA": "LK", "SUR": "SR", "SWE": "SE",
        "CHE": "CH", "TWN": "TW", "TJK": "TJ", "TZA": "TZ", "THA": "TH", "TON": "TO", "TTO": "TT",
        "TUN": "TN", "TUR": "TR", "TKM": "TM", "TCA": "TC", "UGA": "UG", "UKR": "UA", "ARE": "AE",
        "GBR": "GB", "USA": "US", "URY": "UY", "UZB": "UZ", "VUT": "VU", "VEN": "VE", "VNM": "VN",
        "YEM": "YE", "ZMB": "ZM", "ZWE": "ZW",
    ]
}

// MARK: - Snooze

/// "Later" for ONE version. A newer live version is a new fact and shows at once.
struct StoreUpdateSnooze {
    static let versionKey = "upd_store_snoozed_version"
    static let untilKey = "upd_store_snoozed_until"

    let defaults: UserDefaults

    func snooze(_ version: String, now: Date, interval: TimeInterval = StoreUpdateCheck.snoozeInterval) {
        defaults.set(version, forKey: Self.versionKey)
        defaults.set(now.addingTimeInterval(interval).timeIntervalSince1970, forKey: Self.untilKey)
    }

    func isSnoozed(_ version: String, now: Date) -> Bool {
        guard defaults.string(forKey: Self.versionKey) == version else { return false }
        let until = defaults.double(forKey: Self.untilKey)
        // Clock moved back by more than the snooze length: don't hide forever.
        guard until > 0, until - now.timeIntervalSince1970 <= StoreUpdateCheck.snoozeInterval else { return false }
        return now.timeIntervalSince1970 < until
    }
}

// MARK: - The checker

@MainActor
final class StoreUpdateChecker: ObservableObject {

    static let shared = StoreUpdateChecker()

    static let lastCheckKey = "upd_store_last_check"
    static let liveVersionKey = "upd_store_live_version"

    /// The newer live version, when there is one. Survives snooze; the popup
    /// reads `pendingAnnouncement`, which applies it.
    @Published private(set) var availableVersion: String?
    /// Hidden for the rest of this launch after "Update" (if they did update,
    /// the next launch is on the new version and nothing shows).
    @Published private(set) var dismissedThisLaunch = false
    /// Bumped by snooze so SwiftUI re-reads `pendingAnnouncement`.
    @Published private var snoozeGeneration = 0

    private let defaults: UserDefaults
    private let installedVersion: String
    private let bundleID: String
    private let now: () -> Date
    private let fetch: (URL) async throws -> (Data, URLResponse)
    private let countryProvider: () async -> String?
    private var fetchedThisLaunch = false

    init(defaults: UserDefaults = .standard,
         installedVersion: String = AppVersion.current,
         bundleID: String = Bundle.main.bundleIdentifier ?? StoreUpdateCheck.fallbackBundleID,
         now: @escaping () -> Date = Date.init,
         fetch: @escaping (URL) async throws -> (Data, URLResponse) = StoreUpdateChecker.liveFetch,
         countryProvider: @escaping () async -> String? = StoreUpdateChecker.liveCountry) {
        self.defaults = defaults
        self.installedVersion = installedVersion
        self.bundleID = bundleID
        self.now = now
        self.fetch = fetch
        self.countryProvider = countryProvider
        // Start from the last known answer: offline launches behave like the last online one.
        self.availableVersion = StoreUpdateCheck.versionToAnnounce(
            installed: installedVersion, live: defaults.string(forKey: Self.liveVersionKey))
    }

    /// What the popup should announce right now, or nil.
    var pendingAnnouncement: String? {
        _ = snoozeGeneration
        guard !dismissedThisLaunch, let version = availableVersion else { return nil }
        if StoreUpdateSnooze(defaults: defaults).isSnoozed(version, now: now()) { return nil }
        return version
    }

    /// Launch hook. One network call per launch at most, and only if the cached
    /// answer is 12 h old or more. Never throws, never logs per failure.
    func checkIfDue() async {
        guard !fetchedThisLaunch else { return }
        fetchedThisLaunch = true
        let last = defaults.object(forKey: Self.lastCheckKey) as? Double
        guard StoreUpdateCheck.isCheckDue(lastCheck: last.map(Date.init(timeIntervalSince1970:)), now: now()) else {
            return
        }
        let country = await countryProvider()
        guard let url = StoreUpdateCheck.lookupURL(bundleID: bundleID, country: country) else { return }
        let data: Data
        do {
            let (body, response) = try await fetch(url)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200 else { return }
            data = body
        } catch {
            return
        }
        switch StoreUpdateCheck.parse(data) {
        case .unusable:
            // Treated as an outage: keep the previous answer, retry next launch.
            return
        case .notListed:
            defaults.set(now().timeIntervalSince1970, forKey: Self.lastCheckKey)
            defaults.removeObject(forKey: Self.liveVersionKey)
            availableVersion = nil
        case .live(let live):
            defaults.set(now().timeIntervalSince1970, forKey: Self.lastCheckKey)
            defaults.set(live, forKey: Self.liveVersionKey)
            availableVersion = StoreUpdateCheck.versionToAnnounce(installed: installedVersion, live: live)
        }
    }

    func later() {
        guard let version = availableVersion else { return }
        StoreUpdateSnooze(defaults: defaults).snooze(version, now: now())
        snoozeGeneration += 1
    }

    func updateTapped() {
        dismissedThisLaunch = true
    }

    #if DEBUG
    /// Screenshot / simulator aid: `-OSHIStoreUpdatePreview 9.9.9` forces the
    /// announcement without any network. Compiled out of Release.
    func forcePreview(version: String) {
        fetchedThisLaunch = true
        availableVersion = StoreUpdateCheck.versionToAnnounce(installed: installedVersion, live: version)
    }
    #endif

    func resetForTesting() {
        for key in [Self.lastCheckKey, Self.liveVersionKey, StoreUpdateSnooze.versionKey, StoreUpdateSnooze.untilKey] {
            defaults.removeObject(forKey: key)
        }
        fetchedThisLaunch = false
        dismissedThisLaunch = false
        availableVersion = nil
    }

    // MARK: Live dependencies

    nonisolated static func liveFetch(_ url: URL) async throws -> (Data, URLResponse) {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 10
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: config)
        defer { session.finishTasksAndInvalidate() }
        return try await session.data(for: request)
    }

    /// Storefront first (bounded to 3 s: it can stall without an account),
    /// device region second.
    nonisolated static func liveCountry() async -> String? {
        let alpha3 = await storefrontCountry(timeout: 3)
        return StoreUpdateCheck.country(storefrontAlpha3: alpha3,
                                        localeRegion: Locale.current.region?.identifier)
    }

    private nonisolated static func storefrontCountry(timeout: TimeInterval) async -> String? {
        final class Once: @unchecked Sendable {
            private let lock = NSLock()
            private var done = false
            func claim() -> Bool { lock.lock(); defer { lock.unlock() }; if done { return false }; done = true; return true }
        }
        let once = Once()
        return await withCheckedContinuation { (continuation: CheckedContinuation<String?, Never>) in
            Task.detached {
                let code = await Storefront.current?.countryCode
                if once.claim() { continuation.resume(returning: code) }
            }
            Task.detached {
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                if once.claim() { continuation.resume(returning: nil) }
            }
        }
    }
}

// MARK: - Live context probes

enum StoreUpdatePresentationProbe {
    @MainActor
    static func callActive() -> Bool {
        let voice = VoiceCallManager.shared.callState
        if voice == .inCall || voice == .ringing || voice == .connecting { return true }
        switch MeshDirectCallManager.shared.callState {
        case .ringing, .incomingCall, .connecting, .active: return true
        default: break
        }
        #if canImport(CallKit) && !targetEnvironment(macCatalyst)
        // Any call the system knows about, OSHI's or a regular phone call.
        if CXCallObserver().calls.contains(where: { !$0.hasEnded }) { return true }
        #endif
        return false
    }

    @MainActor
    static func otherUIPresented() -> Bool {
        RatePresentationProbe.isBusy() || CallRatingManager.shared.isPresenting
    }
}

// MARK: - The popup

struct StoreUpdateCard: View {
    let version: String
    let onUpdate: () -> Void
    let onLater: () -> Void
    @Environment(\.colorScheme) private var scheme

    /// Brand purple on black is ~2.3:1 — unreadable as text. Dark (and OLED)
    /// gets a lifted lavender of the same hue for the text-only button.
    private var laterColor: Color {
        scheme == .dark ? Color(red: 0.70, green: 0.62, blue: 1.0) : Theme.brand
    }

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "arrow.down.app.fill")
                .font(.system(size: 40, weight: .semibold))
                .foregroundStyle(Theme.brand)
                .accessibilityHidden(true)

            Text(LocalizedOrEnglish.text("upd_available_title"))
                .font(.title3.weight(.bold))
                .multilineTextAlignment(.center)
                .foregroundStyle(.primary)

            Text("upd_available_body".localized(with: version))
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            VStack(spacing: 8) {
                Button(action: onUpdate) {
                    Text(LocalizedOrEnglish.text("upd_available_update"))
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .foregroundStyle(.white)
                        .background(Theme.brand, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .buttonStyle(.plain)
                .keyboardShortcut(.defaultAction)
                .accessibilityIdentifier("upd_available_update")

                Button(action: onLater) {
                    Text(LocalizedOrEnglish.text("upd_available_later"))
                        .font(.body.weight(.medium))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .foregroundStyle(laterColor)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .keyboardShortcut(.cancelAction)
                .accessibilityIdentifier("upd_available_later")
            }
            .padding(.top, 4)
        }
        .padding(.horizontal, 22)
        .padding(.top, 24)
        .padding(.bottom, 14)
        .frame(maxWidth: 340)
        .oledGlassCard(RoundedRectangle(cornerRadius: 26, style: .continuous),
                       fallback: Color(.secondarySystemGroupedBackground),
                       blur: true)
        .shadow(color: .black.opacity(0.18), radius: 24, y: 10)
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
        .accessibilityAction(.escape, onLater)
    }
}

extension View {
    /// Attach once at the scene root. `allowed` is false while the splash,
    /// onboarding or lock screen is up.
    func storeUpdatePrompt(allowed: Bool) -> some View {
        modifier(StoreUpdatePromptModifier(allowed: allowed))
    }
}

private struct StoreUpdatePromptModifier: ViewModifier {
    let allowed: Bool

    @ObservedObject private var checker = StoreUpdateChecker.shared
    @ObservedObject private var supportGate = MinimumVersionGate.shared
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    @State private var shown: String?
    @State private var evaluation: Task<Void, Never>?

    func body(content: Content) -> some View {
        content
            .overlay {
                if let version = shown {
                    ZStack {
                        Color.black.opacity(0.35)
                            .ignoresSafeArea()
                            .contentShape(Rectangle())
                            .onTapGesture {}   // modal: the app behind is not reachable
                            .accessibilityHidden(true)
                        StoreUpdateCard(version: version,
                                        onUpdate: { update() },
                                        onLater: { later() })
                            .padding(.horizontal, 24)
                    }
                    .transition(.opacity.combined(with: .scale(scale: 0.96)))
                    .zIndex(1800)
                }
            }
            .animation(.easeOut(duration: 0.22), value: shown)
            .task {
                #if DEBUG
                let args = ProcessInfo.processInfo.arguments
                if let i = args.firstIndex(of: "-OSHIStoreUpdatePreview"), i + 1 < args.count {
                    checker.forcePreview(version: args[i + 1])
                } else {
                    await checker.checkIfDue()
                }
                #else
                await checker.checkIfDue()
                #endif
                scheduleEvaluation(after: 2.5)
            }
            .onChange(of: checker.pendingAnnouncement) { _, _ in scheduleEvaluation(after: 1.0) }
            .onChange(of: allowed) { _, isAllowed in
                if isAllowed { scheduleEvaluation(after: 1.5) } else { shown = nil }
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { scheduleEvaluation(after: 1.5) }
            }
    }

    /// Waits for a calm screen: retries for about a minute when something else
    /// (a sheet, a call, the rating ask) is up, then gives up until the next
    /// foreground. Nothing is spent by a failed attempt.
    private func scheduleEvaluation(after delay: TimeInterval) {
        evaluation?.cancel()
        evaluation = Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            for attempt in 0..<12 {
                if Task.isCancelled { return }
                if attempt > 0 { try? await Task.sleep(nanoseconds: 5_000_000_000) }
                guard shown == nil, let version = checker.pendingAnnouncement else { return }
                let context = StoreUpdateCheck.Context(
                    mainUIVisible: allowed && scenePhase == .active,
                    callActive: StoreUpdatePresentationProbe.callActive(),
                    otherUIPresented: StoreUpdatePresentationProbe.otherUIPresented(),
                    supportBannerShowing: supportGate.shouldShowPrompt)
                if StoreUpdateCheck.mayPresent(context) {
                    shown = version
                    return
                }
                if !allowed { return }  // onChange(of: allowed) restarts it
            }
        }
    }

    private func later() {
        checker.later()
        shown = nil
    }

    private func update() {
        checker.updateTapped()
        shown = nil
        openURL(StoreUpdateCheck.storeURL) { accepted in
            if !accepted { openURL(StoreUpdateCheck.webStoreURL) }
        }
    }
}
