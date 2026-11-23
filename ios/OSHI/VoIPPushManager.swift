//
//  VoIPPushManager.swift
//  OSHI - VoIP Push Notifications with CallKit
//
//  Enables receiving calls even when app is closed/killed.
//  Uses PushKit for VoIP push notifications and CallKit for native call UI.
//
//  ARCHITECTURE:
//  1. User registers device with VPS (VoIP token + APNs token)
//  2. When call comes in, VPS sends VoIP push to recipient
//  3. PushKit wakes up app (even if killed)
//  4. CallKit shows native incoming call UI
//  5. User can accept/decline from lock screen
//
//  REQUIREMENTS:
//  - Enable "Voice over IP" in Background Modes (Xcode)
//  - Enable "Push Notifications" capability
//  - Add VoIP Push certificate in Apple Developer Portal
//  - Server needs APNs credentials to send pushes
//

import Foundation
import PushKit
import CallKit
import UIKit
import AVFoundation
import CryptoKit
import SwiftUI
import Combine

/// __CALL_PUSH_AUTH_IOS_2026_09_23__ The ONE signer for call_server + push_service requests
/// (docs/CALL_PUSH_AUTH_CONTRACT.md). Loads the Ed25519 key from the Keychain item
/// `signingKey` — the same item `V2KeychainCrypto` signs `/v2/*` with, i.e. the key the server
/// has bound to our identity — and delegates the bytes to ``HTTPRequestSignature/sign(_:with:timestamp:)``.
///
/// The key is cached for 60 s: the call path signs audio/video polls at up to 20 Hz, and a
/// `SecItemCopyMatching` per poll is measurable. 60 s bounds how long a reset identity could
/// keep signing with the previous key (it would be a `mismatch` → 403, never a silent success).
enum CallPushSigner {
    private static let lock = NSLock()
    private static var cached: (key: Curve25519.Signing.PrivateKey, at: Date)?

    static func currentKey() -> Curve25519.Signing.PrivateKey? {
        lock.lock(); defer { lock.unlock() }
        if let c = cached, Date().timeIntervalSince(c.at) < 60 { return c.key }
        guard let data = try? KeychainHelper.load(key: "signingKey"),
              let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: data) else {
            cached = nil
            return nil
        }
        cached = (key, Date())
        return key
    }

    /// Sign in place. Returns `false` when no key is readable (Keychain locked before first
    /// unlock, identity not created yet). Callers must NOT treat that as "send unsigned and
    /// forget": contract §1 — once `enforce` is on an unsigned request is a 403, so a caller
    /// with a retry loop retries, and the others send (still accepted in `dual`) but log it.
    @discardableResult
    static func sign(_ request: inout URLRequest) -> Bool {
        guard let key = currentKey() else {
            OshiLog.call.errorPublic("CallPushSigner: no signing key — request left UNSIGNED: \(request.httpMethod ?? "GET") \(request.url?.path ?? "?")")
            return false
        }
        return HTTPRequestSignature.sign(&request, with: key) != nil
    }

    /// Drop the cached key (identity reset / restore).
    static func invalidate() {
        lock.lock(); cached = nil; lock.unlock()
    }
}

// MARK: - PushKit-queue gate (__PUSHKIT_OFF_MAIN_2026_09_23__)

/// Lock-guarded snapshots of the MAIN-THREAD state the VoIP push handler has to consult
/// before it reports to CallKit: VoiceCallManager's call state and recently-ended callIds,
/// the blocked / stale-identity key sets, the contact keys (contacts-only mode) and the
/// app state. Everything here is written from main (didSet / Combine sinks /
/// UIApplication notifications) and read from `VoIPPushManager.pushQueue`, so the
/// handler never has to wait for main — the whole point: a VoIP push that is not
/// reported in time gets the app killed (FRONTBOARD 0xBAADCA11, 2026-09-22).
///
/// Keys are folded with `MessageManager.normalizeAliasKey` (base64url/padding) — the same
/// fold BlockedContactsManager and StaleIdentityManager compare with.
final class CallPushGate {
    static let shared = CallPushGate()

    static let contactKeysDefaultsKey = "oshi.callPushGate.contactKeys.v1"

    private let lock = NSLock()
    private var _callState: CallState = .idle
    private var _endedCallIds: [String: Date] = [:]   // callId → expiry
    private var _blockedKeys: Set<String> = []
    private var _staleKeys: Set<String> = []
    private var _contactKeys: Set<String>?
    private var _appState: UIApplication.State = .background

    init(defaults: UserDefaults = .standard) {
        if let stored = defaults.array(forKey: Self.contactKeysDefaultsKey) as? [String] {
            _contactKeys = Set(stored)
        }
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock(); defer { lock.unlock() }
        return body()
    }

    static func fold(_ key: String) -> String { MessageManager.normalizeAliasKey(key) }

    // VoiceCallManager mirror
    var callState: CallState { locked { _callState } }
    func setCallState(_ state: CallState) { locked { _callState = state } }

    func noteCallEnded(_ callId: String, ttl: TimeInterval, now: Date = Date()) {
        locked {
            _endedCallIds[callId] = now.addingTimeInterval(ttl)
            _endedCallIds = _endedCallIds.filter { $0.value > now }
        }
    }
    func isCallIdRecentlyEnded(_ callId: String?, now: Date = Date()) -> Bool {
        guard let id = callId, !id.isEmpty else { return false }
        return locked { (_endedCallIds[id].map { $0 > now }) ?? false }
    }

    // Blocked / stale identity
    func setBlockedKeys<S: Sequence>(_ keys: S) where S.Element == String {
        let folded = Set(keys.map(Self.fold))
        locked { _blockedKeys = folded }
    }
    func setStaleKeys<S: Sequence>(_ keys: S) where S.Element == String {
        let folded = Set(keys.map(Self.fold))
        locked { _staleKeys = folded }
    }
    func isBlocked(_ key: String) -> Bool { let k = Self.fold(key); return locked { _blockedKeys.contains(k) } }
    func isStale(_ key: String) -> Bool {
        let k = Self.fold(key)
        if k.isEmpty { return false }
        return locked { _staleKeys.contains(k) }
    }

    // Contacts (contacts-only mode). nil = never populated on this install.
    /// Returns true when the persisted set changed (so the caller can decide to save).
    @discardableResult
    func setContactKeys<S: Sequence>(_ keys: S, persistTo defaults: UserDefaults? = .standard) -> Bool where S.Element == String {
        let folded = Set(keys.map(Self.fold).filter { !$0.isEmpty })
        let changed: Bool = locked {
            if _contactKeys == folded { return false }
            _contactKeys = folded
            return true
        }
        if changed { defaults?.set(Array(folded), forKey: Self.contactKeysDefaultsKey) }
        return changed
    }
    func isContact(_ key: String) -> Bool? {
        let k = Self.fold(key)
        return locked { _contactKeys.map { $0.contains(k) } }
    }

    // App state
    var appState: UIApplication.State { locked { _appState } }
    func setAppState(_ state: UIApplication.State) { locked { _appState = state } }
}

/// Runs a PushKit completion exactly once, whichever of CallKit's callback or the
/// fallback timer gets there first. `fire()` returns true for the call that ran it.
final class PushCompletionOnce {
    private let lock = NSLock()
    private var block: (() -> Void)?
    init(_ block: @escaping () -> Void) { self.block = block }
    @discardableResult
    func fire() -> Bool {
        lock.lock()
        let b = block
        block = nil
        lock.unlock()
        b?()
        return b != nil
    }
}

// MARK: - VoIP Push Manager

class VoIPPushManager: NSObject, ObservableObject {
    static let shared = VoIPPushManager()

    /// 🔧 MULTI-DEVICE: Stable per-PHYSICAL-device UUID.
    ///
    /// Stored in the Keychain with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`
    /// so it is NEVER carried across iCloud Backup / Quick Start / device restore.
    /// The previous implementation used UserDefaults, which is iCloud-backed:
    /// restoring iPhone-B from iPhone-A's backup gave both phones the SAME
    /// `oshi_device_id`, which on the server side caused (a) the second iPhone's
    /// VoIP-token registration to overwrite the first (only one phone could ring),
    /// and (b) the message-sync pull endpoint to return `same_device` and never
    /// deliver sent messages between the two devices.
    ///
    /// Migration: if a UserDefaults value still exists, we IGNORE it (it may be
    /// shared with another physical device) and seed a fresh deviceId from
    /// `identifierForVendor`, then persist to Keychain.
    /// « sandbox » ou « production », lu dans l'entitlement `aps-environment` du
    /// profil embarqué — la seule source qui dise la vérité.
    ///
    /// Apple écrit `aps-environment: development` dans tout profil de
    /// développement ou ad-hoc, et `production` pour l'App Store et TestFlight.
    /// C'est exactement la distinction qui décide quel jeton iOS délivre.
    ///
    /// Repli sur l'ancien comportement si le profil est illisible — un build
    /// App Store n'embarque pas toujours de `.mobileprovision`, et là
    /// « production » est juste.
    static func detectedAPNsEnvironment() -> String {
        if let value = embeddedAPSEnvironment() {
            return value == "development" ? "sandbox" : "production"
        }
        #if DEBUG
        return "sandbox"
        #else
        return "production"
        #endif
    }

    /// Lit `Entitlements.aps-environment` dans `embedded.mobileprovision`.
    ///
    /// Le fichier est un conteneur CMS: le plist est encadré par `<plist` et
    /// `</plist>`. On découpe sur ces bornes plutôt que d'ajouter une dépendance
    /// de décodage CMS pour un seul champ.
    ///
    /// __CATALYST_PROFILE_IS_NOT_MOBILEPROVISION_2026_10_19__ Le Mac range son
    /// profil AILLEURS et sous un AUTRE NOM: `Contents/embedded.provisionprofile`,
    /// pas `embedded.mobileprovision` dans Resources. La recherche échouait donc
    /// toujours sur Mac, on retombait sur `#if DEBUG`, et une build **Release**
    /// signée avec un profil de **développement** annonçait `production`.
    ///
    /// Conséquence mesurée (19/10): le serveur poussait vers `api.push.apple.com`
    /// un jeton délivré par le bac à sable ⇒ `400 BadDeviceToken` sur CHAQUE push
    /// vers le Mac — zéro notification, 1:1 comme groupe. Le profil embarqué est
    /// la seule source qui dit la vérité: la configuration de build ne sait pas
    /// avec quel profil on a signé.
    private static func embeddedAPSEnvironment() -> String? {
        let candidates = [
            Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
            // macOS/Catalyst
            Bundle.main.bundleURL.appendingPathComponent("Contents/embedded.provisionprofile"),
            Bundle.main.url(forResource: "embedded", withExtension: "provisionprofile"),
        ].compactMap { $0 }
        guard let url = candidates.first(where: { FileManager.default.fileExists(atPath: $0.path) }),
              let raw = try? Data(contentsOf: url),
              let text = String(data: raw, encoding: .isoLatin1),
              let start = text.range(of: "<plist"),
              let end = text.range(of: "</plist>")
        else { return nil }

        let plistText = String(text[start.lowerBound..<end.upperBound])
        guard let plistData = plistText.data(using: .isoLatin1),
              let plist = try? PropertyListSerialization.propertyList(from: plistData, format: nil) as? [String: Any],
              let entitlements = plist["Entitlements"] as? [String: Any],
              let aps = entitlements["aps-environment"] as? String
        else { return nil }
        return aps
    }

    static func getDeviceId() -> String {
        let keychainKey = "oshi_device_id_v2"
        if let data = try? KeychainHelper.load(key: keychainKey),
           let existing = String(data: data, encoding: .utf8),
           !existing.isEmpty {
            return existing
        }
        // Seed from identifierForVendor (guaranteed unique to physical device,
        // not shared across iCloud restore) — fall back to UUID if unavailable
        // (e.g., during very early launch before the first scene is connected).
        let seed = UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
        if let data = seed.data(using: .utf8) {
            try? KeychainHelper.save(key: keychainKey, data: data)
        }
        // Clear the legacy UserDefaults key so subsequent reads can't accidentally
        // pick up a value cloned from another device.
        UserDefaults.standard.removeObject(forKey: "oshi_device_id")
        return seed
    }

    /// Add Ed25519 signature headers to an HTTP request (call_server / push_service).
    /// __CALL_PUSH_AUTH_IOS_2026_09_23__ delegates to the shared ``CallPushSigner`` so the
    /// path spelling, body bytes and key are the contract's (docs/CALL_PUSH_AUTH_CONTRACT.md).
    @discardableResult
    fileprivate func signRequest(_ request: inout URLRequest) -> Bool {
        CallPushSigner.sign(&request)
    }
    
    /// __BLIND_PUSH_2026_11_09__ No cookies, no cache, no shared connection state with the
    /// signed push-service session: the gateway learns a token, never who owns it.
    static let blindPushSealSession: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.httpCookieStorage = nil
        c.urlCache = nil
        c.timeoutIntervalForRequest = 8
        return URLSession(configuration: c)
    }()

    // Published state
    @Published var voipToken: String?
    @Published var apnsToken: String?
    @Published var isRegistered = false
    
    // PushKit registry
    private var voipRegistry: PKPushRegistry?

    // 🔧 FIX: Delayed registration to avoid sending empty APNs token
    private var registrationWorkItem: DispatchWorkItem?
    
    // CallKit provider for native call UI
    private var callProvider: CXProvider?
    private var callController: CXCallController?
    /// __BLOCKED_NO_RECENTS_2026_09_24__ blocked-caller throwaways in flight with Recents off.
    fileprivate let recentsLock = NSLock()
    fileprivate var recentsHiddenHolds = 0
    
    // __PUSHKIT_OFF_MAIN_2026_09_23__ The call identity below is read and written from
    // TWO queues now: PushKit delivers on `pushQueue` (so a busy main thread can never
    // delay the mandatory `reportNewIncomingCall`), while CallKit delegate callbacks and
    // the rest of the app stay on main. Every field lives behind `callStateLock`.
    //
    // MEASURED 2026-09-22 (build 143, iPhone): FRONTBOARD 0xBAADCA11 "bad call" kill
    // while the MAIN thread was inside JSONEncoder.encode in
    // `MultiDeviceSyncManager.uploadMessagesForSync()` — the registry was created with
    // `queue: .main`, so a VoIP push that arrived during that encode could not even be
    // DELIVERED to our delegate, let alone reported, before iOS's deadline.
    private let callStateLock = NSLock()
    private func withCallState<T>(_ body: () -> T) -> T {
        callStateLock.lock(); defer { callStateLock.unlock() }
        return body()
    }

    // Current call UUID (for CallKit)
    private var _currentCallUUID: UUID?
    private var currentCallUUID: UUID? {
        get { withCallState { _currentCallUUID } }
        set { withCallState { _currentCallUUID = newValue } }
    }
    // Readable so the background/locked caller-cancel path can verify a callEnd
    // belongs to the caller CallKit is currently ringing before it tears down.
    private var _currentCallerPublicKey: String?
    private(set) var currentCallerPublicKey: String? {
        get { withCallState { _currentCallerPublicKey } }
        set { withCallState { _currentCallerPublicKey = newValue } }
    }
    private var _currentCallerName: String?
    private var currentCallerName: String? {
        get { withCallState { _currentCallerName } }
        set { withCallState { _currentCallerName = newValue } }
    }

    /// Thread-safe mirror of `callKitPresentingCallId`, written SYNCHRONOUSLY by
    /// `setCallKitPresenting` on whatever queue calls it. The `@Published` twin drives
    /// SwiftUI and therefore only moves on main — reading it from `pushQueue` could see
    /// a value one main-queue turn stale (or, with main blocked, many seconds stale),
    /// which is exactly the window a second push would mint a second surface in.
    private var _presentingCallIdSync: String?
    private var presentingCallIdSync: String? {
        get { withCallState { _presentingCallIdSync } }
        set { withCallState { _presentingCallIdSync = newValue } }
    }

    /// The queue PushKit delivers VoIP pushes on. NEVER main (see above).
    static let pushQueue = DispatchQueue(label: "com.oshi.voip.pushkit", qos: .userInteractive)
    /// CXProvider's delegate queue (the proxy hops each callback to main).
    static let callKitDelegateQueue = DispatchQueue(label: "com.oshi.voip.callkit", qos: .userInteractive)
    /// CXProvider holds its delegate weakly.
    private var callKitDelegateProxy: CallKitMainHopDelegate?

    /// __STALE_LIVE_UUID_2026_09_25__ Ground truth for "is this UUID a call iOS still
    /// has?". Our own `currentCallUUID`/`presentingCallIdSync` can outlive the CallKit
    /// call (a teardown path that missed the reset). Coalescing a new push onto such a
    /// dead UUID reports NO new call ⇒ FRONTBOARD 0xBAADCA11: measured 25/09 19:23:30 on
    /// the iPhone 17 (build 148), 8 s after Natalia's call push, main thread idle.
    private let callObserver = CXCallObserver()
    /// When our code minted the current UUID. CallKit registers a reported call
    /// asynchronously, so a duplicate push milliseconds later may not see it in
    /// `callObserver.calls` yet — within this grace window the UUID counts as live.
    private var currentUUIDMintedAt: Date = .distantPast
    private func callKitStillHas(_ uuid: UUID) -> Bool {
        if Date().timeIntervalSince(currentUUIDMintedAt) < 15 { return true }
        return callObserver.calls.contains { $0.uuid == uuid && !$0.hasEnded }
    }

    /// Report to CallKit from ANY queue (the report itself is synchronous on the caller's
    /// queue, as PushKit requires); `then` runs on MAIN, where every reply handler in this
    /// file was written to run when the provider's delegate queue was main.
    private func reportNewIncomingCallFromAnyQueue(_ uuid: UUID, _ update: CXCallUpdate,
                                                   then: @escaping (Error?) -> Void) {
        callProvider?.reportNewIncomingCall(with: uuid, update: update) { error in
            DispatchQueue.main.async { then(error) }
        }
    }

    /// True when a CallKit call is currently being handled (incoming, ringing, or active).
    /// Used by NotificationDelegate to suppress duplicate banner notifications.
    var hasActiveCallUUID: Bool { currentCallUUID != nil }

    // 🔧 FIX (2026-07-27) — SINGLE SOURCE OF TRUTH for "CallKit is presenting the
    // incoming-call surface for this call".
    //
    // MECHANISM of the bug this replaces: several views each decided on their own
    // whether to draw the in-app incoming-call UI (ContentView's ZStack branch, the
    // IncomingCallOverlay ViewModifier, a UIWindow built here). Only the ViewModifier
    // consulted `VoiceCallManager.isCallKitHandlingIncoming` — and that modifier was
    // never installed on any view, so the guard could not hold. The surface that IS
    // live (ContentView) had no guard at all, so a VoIP-push call drew BOTH the
    // CallKit banner (foreground CallKit UI) and our full-screen overlay.
    //
    // This is the only flag any view may consult, and it is written in exactly one
    // place: around `callProvider.reportNewIncomingCall`. Set optimistically the
    // instant we hand the call to CallKit, cleared the moment CallKit stops owning
    // the ringing surface (report failed, answered, ended, provider reset). Nil
    // therefore means "no CallKit ring on screen — the in-app overlay is the UI".
    //
    // It is deliberately NOT `VoiceCallManager.isCallKitHandlingIncoming`: that flag
    // is also raised by the `VoIPPushReceived` notification *before* iOS confirms the
    // report, so it stays true even when `reportNewIncomingCall` FAILS — in which
    // case the in-app overlay is the only surface and must be allowed to show.
    @Published private(set) var callKitPresentingCallId: String?

    /// True while CallKit owns the incoming-call UI. Views MUST NOT draw the in-app
    /// incoming-call overlay while this is true.
    var isCallKitPresentingIncoming: Bool { callKitPresentingCallId != nil }

    /// Who is allowed to draw the incoming-call UI right now. This is THE decision,
    /// exposed as one value so no view has to assemble it from several booleans.
    /// `IncomingCallOverlayView` enforces it on itself (see IncomingCallOverlay.swift),
    /// so a presenter that forgets to check still cannot draw over CallKit.
    enum IncomingCallSurfaceOwner: String { case callKit, inApp }

    var incomingCallSurfaceOwner: IncomingCallSurfaceOwner {
        callKitPresentingCallId == nil ? .inApp : .callKit
    }

    /// Write the single source of truth on the main thread (it drives SwiftUI) and
    /// keep `VoiceCallManager.isCallKitHandlingIncoming` in sync for the existing
    /// non-UI consumers (ringtone suppression, mesh-signal dedup).
    ///
    /// 🔧 FIX (2026-07-27b): every transition is logged. The duplicate-surface bug
    /// survived a first fix attempt precisely because neither surface left a trace:
    /// the log showed CallKit being reported and the call ringing, and nothing at all
    /// about who was actually on screen. `reason` says WHY the surface moved, so the
    /// next occurrence is diagnosable from call_debug.log alone.
    private func setCallKitPresenting(_ callId: String?, reason: String) {
        // __PUSHKIT_OFF_MAIN_2026_09_23__ the lock-guarded mirror moves NOW, on the
        // caller's queue; the published value follows on main and always converges to
        // the mirror (it re-reads it rather than trusting the captured argument, so a
        // main-thread write that lands between this call and the hop cannot be undone
        // by a stale off-main value).
        presentingCallIdSync = callId
        let apply = {
            let callId = self.presentingCallIdSync
            let previous = self.callKitPresentingCallId
            // Log TRANSITIONS only, so a repeated write (report_pending → report_ok
            // for the same call) doesn't drown the useful lines.
            if previous != callId {
                self.callKitPresentingCallId = callId
                if let callId {
                    CallFileLogger.shared.log("📞 SURFACE +callkit callId=\(callId.prefix(8)) reason=\(reason)")
                } else {
                    CallFileLogger.shared.log("📞 SURFACE -callkit callId=\(previous?.prefix(8) ?? "nil") reason=\(reason)")
                }
            }
            // Deliberately OUTSIDE the transition check: the shipped code re-asserted
            // this on every write, and paths in between (a failed report, an ended
            // call) set it false — so skipping the re-assert would silently change
            // ringtone-suppression / mesh-dedup behaviour.
            if callId != nil { VoiceCallManager.shared.isCallKitHandlingIncoming = true }
        }
        if Thread.isMainThread { apply() } else { DispatchQueue.main.async(execute: apply) }
    }

    // 🔧 FIX (2026-07-27b): `coalesceDuplicatePushIntoSameCall` /
    // `callkit_coalesce_duplicate_push` is GONE. fd48232 added coalescing but left it
    // opt-in and OFF, so on the phones the duplicate push still reported a fresh
    // placeholder CXCall — which is the second surface the owner saw. Coalescing is
    // now the default and lives in `satisfyPushObligation`, which also covers the five
    // OTHER paths that reported throwaway calls (stale, blocked, stale identity,
    // contacts-only, already-in-a-call, already-ringing). The PushKit risk that kept
    // it opt-in is handled there by falling back to a placeholder whenever the
    // same-UUID report fails with anything other than `callUUIDAlreadyExists`.
    // The escape hatch is now `callkit_placeholder_for_extra_push` (default false).

    // 🔧 VPS endpoints
    // Push service: port 8084 (needs /push/ route in nginx OR direct port access)
    // Call service: port 8083 (accessible via /api/call/ nginx route)
    // NOTE: If firewall blocks direct ports, nginx must have these routes configured
    // DEBUG-only override (simulator self-tests against local servers); nil in Release.
    private let pushServiceURL = WebSessionManager.debugOverride("OSHI_PUSH_BASE_URL") ?? "https://oshi-messenger.com/push"  // via nginx proxy
    private let callServiceURL = "https://oshi-messenger.com/api/call"  // via nginx proxy

    // Fallback to direct ports if nginx routes not configured
    private let pushServiceDirectURL = WebSessionManager.debugOverride("OSHI_PUSH_BASE_URL") ?? "https://oshi-messenger.com/push"
    private let callServiceDirectURL = "https://oshi-messenger.com/api/call"

    // Legacy URL for backward compatibility
    private var vpsURL: String { pushServiceURL }
    
    // Pending call data (received via push before app fully launched)
    private var _pendingCallData: [String: Any]?
    private var pendingCallData: [String: Any]? {
        get { withCallState { _pendingCallData } }
        set { withCallState { _pendingCallData = newValue } }
    }

    // 🔧 FIX: Track if we've already sent the call signal notification for current call
    // This prevents duplicate calls when both pollForCallSignal and processPendingCall fire
    private var hasNotifiedCallSignal = false
    private var lastNotifiedCallerId: String?

    // 🔧 FIX: Track processed call IDs to prevent duplicate VoIP push handling
    // This prevents the same call from being reported multiple times
    private var _processedCallIds = Set<String>()
    private var processedCallIds: Set<String> {
        get { withCallState { _processedCallIds } }
        set { withCallState { _processedCallIds = newValue } }
    }
    private var _lastProcessedCallTime: Date?
    private var lastProcessedCallTime: Date? {
        get { withCallState { _lastProcessedCallTime } }
        set { withCallState { _lastProcessedCallTime = newValue } }
    }

    /// Atomic "first time we see this callId?" for the PushKit queue: prunes the window,
    /// tests and inserts under ONE lock hold, so two pushes racing on different queues
    /// (PushKit + the APNs fallback on main) cannot both be told "new".
    private func markCallIdProcessed(_ callId: String) -> Bool {
        withCallState {
            if let last = _lastProcessedCallTime, Date().timeIntervalSince(last) > callIdDeduplicationWindow {
                _processedCallIds.removeAll()
            }
            if _processedCallIds.contains(callId) { return false }
            _processedCallIds.insert(callId)
            _lastProcessedCallTime = Date()
            return true
        }
    }
    // Was 30s — too short: a re-delivered push for the SAME callId after 30s
    // re-rang the phone. 150s comfortably outlives a call's ~55s ring lifetime,
    // so a duplicate push for a call we already handled never rings again. Truly
    // stale pushes are caught by the callTimestamp age check above.
    private let callIdDeduplicationWindow: TimeInterval = 150.0

    // 🔧 FIX (2026-07-27): the UIWindow overlay that used to live here (a third
    // IncomingCallOverlayView, hosted in its own UIWindow at .alert+1 so it would
    // stack ON TOP of CallKit's banner) is GONE. It had no callers, but it encoded
    // exactly the wrong rule — "draw our UI over CallKit's" — and was one edit away
    // from resurrecting the two-surfaces-at-once bug. CallKit now owns the incoming
    // surface whenever it is presenting; see `callKitPresentingCallId`.

    // MARK: - Base64URL Encoding/Decoding Helpers
    // 🔧 CRITICAL: Use base64url encoding for public keys in URLs
    private func base64urlEncode(_ string: String) -> String {
        return string
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    // Decode base64url back to standard base64
    private func base64urlDecode(_ base64urlString: String) -> String {
        var result = base64urlString
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while result.count % 4 != 0 {
            result.append("=")
        }
        return result
    }

    // Normalize a key for comparison (convert both formats to standard base64)
    private func normalizeKey(_ key: String) -> String {
        return base64urlDecode(key)
    }

    override init() {
        super.init()

        // __PUSHKIT_OFF_MAIN_2026_09_23__ fill the gate BEFORE the registry exists, so the
        // first push (possibly the one that cold-launched us) already sees the blocked /
        // stale / contact sets.
        if Thread.isMainThread {
            bindCallPushGateSources()
        } else {
            DispatchQueue.main.async { [weak self] in self?.bindCallPushGateSources() }
        }

        // 🇨🇳 China: Skip CallKit & PushKit entirely (MIIT regulation)
        // PushKit requires CallKit since iOS 13 — if we can't use CallKit, we can't use PushKit
        if !ChinaRegionDetector.shared.shouldBypassCallKit {
            setupCallKit()
            setupVoIPPush()
        } else {
            let reason = ChinaRegionDetector.shared.bypassReason
            OshiLog.call.info("📞 VoIPPush: CallKit & PushKit DISABLED (\(reason))")
            OshiLog.call.info("   Incoming calls will use APNs push + in-app UI instead")
        }

        // Listen for app becoming active to handle pending calls
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAppDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )

        // NOTE: CallKitAnswerCall is handled by VoiceCallManager, not here
        // This prevents duplicate call accept attempts

        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-OSHIVoIPMainBlockedSelfTest") {
            DispatchQueue.main.async { VoIPMainBlockedSelfTest.shared.run() }
        }
        #endif

        OshiLog.call.info("📞 VoIPPushManager: Initialized (bypass CallKit: \(ChinaRegionDetector.shared.shouldBypassCallKit), reason: \(ChinaRegionDetector.shared.bypassReason))")
        OshiLog.call.info("   Push Service: \(pushServiceURL)")
        OshiLog.call.info("   Call Service: \(callServiceURL)")
    }
    
    // MARK: - PushKit gate sources (__PUSHKIT_OFF_MAIN_2026_09_23__)

    private var gateCancellables = Set<AnyCancellable>()
    private var conversationsCancellable: AnyCancellable?
    private weak var gateBoundMessageManager: MessageManager?

    /// Main thread only. Mirrors everything the PushKit handler must consult into
    /// `CallPushGate` (VoiceCallManager mirrors its own state from its `didSet`s).
    private func bindCallPushGateSources() {
        dispatchPrecondition(condition: .onQueue(.main))
        let gate = CallPushGate.shared
        gate.setAppState(UIApplication.shared.applicationState)

        let transitions: [(Notification.Name, UIApplication.State)] = [
            (UIApplication.didBecomeActiveNotification, .active),
            (UIApplication.willResignActiveNotification, .inactive),
            (UIApplication.didEnterBackgroundNotification, .background),
            (UIApplication.willEnterForegroundNotification, .inactive),
        ]
        for (name, state) in transitions {
            NotificationCenter.default.publisher(for: name)
                .sink { [weak self] _ in
                    gate.setAppState(state)
                    self?.bindConversationsIfNeeded()
                }
                .store(in: &gateCancellables)
        }

        BlockedContactsManager.shared.$blockedContacts
            .sink { gate.setBlockedKeys($0.map(\.publicKey)) }
            .store(in: &gateCancellables)
        StaleIdentityManager.shared.$staleIdentities
            .sink { gate.setStaleKeys($0.map(\.publicKey)) }
            .store(in: &gateCancellables)

        bindConversationsIfNeeded()
    }

    /// `MessageManager.shared` is created after us on a normal launch; bind as soon as it
    /// exists (re-checked on every app-state transition) and re-bind if it is replaced.
    private func bindConversationsIfNeeded() {
        guard Thread.isMainThread, let mm = MessageManager.shared, mm !== gateBoundMessageManager else { return }
        gateBoundMessageManager = mm
        conversationsCancellable = mm.$conversations
            .map { $0.map(\.participantPublicKey) }
            .removeDuplicates()
            .sink { CallPushGate.shared.setContactKeys($0) }
    }

    // MARK: - Setup
    
    private func setupVoIPPush() {
        // __PUSHKIT_OFF_MAIN_2026_09_23__ was `queue: .main`. A VoIP push must be
        // reported to CallKit before the delegate returns, and delivery on main made
        // that depend on whatever main was doing (a multi-second JSON encode on
        // 2026-09-22 → FRONTBOARD 0xBAADCA11 kill). The handler below only touches
        // lock-guarded state and `CallPushGate` snapshots; UI work hops to main async.
        voipRegistry = PKPushRegistry(queue: VoIPPushManager.pushQueue)
        voipRegistry?.delegate = self
        voipRegistry?.desiredPushTypes = [.voIP]
        
        OshiLog.call.info("📞 VoIPPush: Registry created, waiting for token...")
    }
    
    private func setupCallKit() {
        // Configure CallKit
        let config = CXProviderConfiguration()
        config.supportsVideo = true  // 🔧 FIX: Enable video support for video calls
        config.maximumCallsPerCallGroup = 1
        config.maximumCallGroups = 1
        config.supportedHandleTypes = [.generic]
        config.includesCallsInRecents = true
        
        // Custom ringtone for CallKit incoming call screen (FaceTime-like)
        config.ringtoneSound = "ringtone.caf"
        
        // App icon for call UI
        if let iconImage = UIImage(named: "AppIcon") {
            config.iconTemplateImageData = iconImage.pngData()
        }
        
        callProvider = CXProvider(configuration: config)
        // __PUSHKIT_OFF_MAIN_2026_09_23__ the provider's delegate queue is PRIVATE, and a
        // proxy re-posts every callback to main asynchronously — exactly how CallKit
        // delivered them before (async, on main, in order), so the handlers below are
        // unchanged. What changes is that CallKit's own machinery (report replies, action
        // delivery) never has to wait for a free main thread; with `queue: .main`, a report
        // made from the PushKit queue still had its reply parked behind a blocked main.
        let proxy = CallKitMainHopDelegate(target: self)
        callKitDelegateProxy = proxy
        callProvider?.setDelegate(proxy, queue: VoIPPushManager.callKitDelegateQueue)
        
        callController = CXCallController()
        
        OshiLog.call.info("📞 CallKit: Provider configured")
    }
    
    // MARK: - Token Registration with VPS
    
    /// Register device tokens with VPS for push notifications
    func registerTokensWithVPS() {
        CallFileLogger.shared.log("📞 VoIPPush: registerTokensWithVPS() called voipToken=\(voipToken != nil) apnsToken=\(apnsToken != nil)")
        // Check if push notifications are enabled in settings
        let pushEnabled = UserDefaults.standard.object(forKey: "pushNotificationsEnabled") as? Bool ?? true
        guard pushEnabled else {
            OshiLog.call.info("🔕 VoIPPush: Push notifications disabled in settings - skipping registration")
            CallFileLogger.shared.log("🔕 VoIPPush: Push disabled in settings — skipping registration")
            return
        }

        // Force re-registration when app version changes (ensures fresh tokens after server updates)
        let currentVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let lastRegisteredVersion = UserDefaults.standard.string(forKey: "lastPushRegisteredVersion")
        if lastRegisteredVersion != currentVersion {
            OshiLog.call.info("📞 VoIPPush: App version changed (\(lastRegisteredVersion ?? "nil") → \(currentVersion)), forcing re-registration")
        }

        let isChinaMode = ChinaRegionDetector.shared.shouldBypassCallKit

        // 🔧 FIX: Message-push registration must NOT be gated on the VoIP token.
        // VoIP (calls) is provisioned separately by PushKit and may never arrive
        // or get invalidated by iOS (OS update / entitlement change). When the
        // VoIP token gated registration, such a device silently stopped receiving
        // MESSAGE banners even though its APNs token was perfectly valid — the
        // recipient kept getting messages via foreground polling but no pushes.
        // Register as long as we have at least one usable token; the server
        // accepts an empty voipToken and uses the APNs token for message pushes.
        guard voipToken != nil || apnsToken != nil else {
            OshiLog.call.info("⚠️ VoIPPush: No tokens yet (neither VoIP nor APNs) — waiting")
            return
        }

        // Get user's public key
        guard let publicKey = UserDefaults.standard.string(forKey: "publicKey"),
              !publicKey.isEmpty else {
            OshiLog.call.info("⚠️ VoIPPush: No public key - user not registered")
            return
        }

        // Check if VoIP push is enabled
        let voipEnabled = UserDefaults.standard.object(forKey: "voipPushEnabled") as? Bool ?? true

        // 🔴 __APNS_ENV_FROM_PROFILE_2026_08_22__ L'environnement APNs se lit dans
        // le PROFIL DE PROVISIONNEMENT, jamais dans la configuration de build.
        //
        // C'était `#if DEBUG ? sandbox : production`. Or les deux axes sont
        // INDÉPENDANTS: c'est la SIGNATURE qui décide quel jeton iOS délivre, pas
        // le fait d'avoir compilé en Debug ou en Release. Un build Release signé
        // en développement — exactement ce qu'on installe sur un appareil de test
        // — reçoit un jeton SANDBOX et déclarait « production ».
        //
        // Apple répond alors `400 BadDeviceToken`, et le serveur SUPPRIME le
        // jeton (push_service.js: « Removed invalid token device »). L'appareil
        // disparaît du registre et ne reçoit plus AUCUNE notification, sans que
        // rien ne le dise côté app.
        //
        // Constaté le 2026-08-22 sur l'iPhone de Hugo: `devices=0 apns=0 fcm=0`
        // pour sa clé, trois suppressions dans la journée, et zéro notification.
        let apnsEnvironment = VoIPPushManager.detectedAPNsEnvironment()

        // 🔧 MULTI-DEVICE: Include deviceId so server stores per-device tokens
        let deviceId = Self.getDeviceId()

        let payload: [String: Any] = [
            "publicKey": publicKey,
            "deviceId": deviceId,
            "voipToken": (!isChinaMode && voipEnabled) ? (voipToken ?? "") : "",  // 🇨🇳 No VoIP token for China
            "apnsToken": apnsToken ?? "",
            "platform": "ios",
            "voipEnabled": !isChinaMode && voipEnabled,
            "isChina": isChinaMode,  // 🇨🇳 Tell server to use APNs for calls instead of VoIP push
            "apnsEnvironment": apnsEnvironment,  // Tell server which APNs endpoint to use
            "appVersion": Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        ]

        guard JSONSerialization.isValidJSONObject(payload) else { return }

        // __BLIND_PUSH_2026_11_09__ Tokens this registration may hand to the push gateway:
        // exactly the ones the legacy payload carries (VoIP blanked in China mode / VoIP off).
        var blindTokens: [BlindPushRegistrar.Token] = []
        if let t = apnsToken, !t.isEmpty { blindTokens.append(.init(kind: .message, token: t, env: apnsEnvironment)) }
        if !isChinaMode && voipEnabled, let t = voipToken, !t.isEmpty { blindTokens.append(.init(kind: .voip, token: t, env: apnsEnvironment)) }

        // Try nginx proxy first, then fallback to direct port (same host today).
        let bases = pushServiceURL == pushServiceDirectURL ? [pushServiceURL] : [pushServiceURL, pushServiceDirectURL]

        Task {
            for base in bases {
                // Push service requests are signed (CALL_PUSH_AUTH contract); the seal
                // request to the gateway is NOT — a signature would name the identity.
                let outcome = await BlindPushRegistrar.register(
                    pushBaseURL: base,
                    legacyPayload: payload,
                    tokens: blindTokens,
                    cachedActive: UserDefaults.standard.bool(forKey: BlindPushRegistrar.activeDefaultsKey),
                    setActive: { UserDefaults.standard.set($0, forKey: BlindPushRegistrar.activeDefaultsKey) },
                    pushSend: { request in
                        var r = request
                        self.signRequest(&r)
                        guard let (data, resp) = try? await URLSession.shared.data(for: r) else { return nil }
                        return BlindPushRegistrar.HTTPResult(status: (resp as? HTTPURLResponse)?.statusCode ?? 0, body: data)
                    },
                    sealSend: { request in
                        guard let (data, resp) = try? await VoIPPushManager.blindPushSealSession.data(for: request) else { return nil }
                        return BlindPushRegistrar.HTTPResult(status: (resp as? HTTPURLResponse)?.statusCode ?? 0, body: data)
                    },
                    log: { CallFileLogger.shared.log("📞 VoIPPush: \($0)") }
                )
                if outcome == .blindAccepted || outcome == .legacyRegistered {
                    await MainActor.run {
                        self.isRegistered = true
                    }
                    // Save registered version to detect future version changes
                    UserDefaults.standard.set(currentVersion, forKey: "lastPushRegisteredVersion")
                    OshiLog.call.info("✅ VoIPPush: Device registered (\(outcome == .blindAccepted ? "blind handles" : "legacy tokens"))")
                    OshiLog.call.info("   Public Key: \(publicKey.prefix(12))...")
                    CallFileLogger.shared.log("✅ VoIPPush: Registered with VPS (\(outcome)) pubKey=\(publicKey.prefix(12))")
                    return
                }
                OshiLog.call.info("⚠️ VoIPPush: Registration attempt via \(base) ended \(String(describing: outcome))")
                CallFileLogger.shared.log("⚠️ VoIPPush: Registration via \(base) ended \(outcome)")
            }
            OshiLog.call.info("❌ VoIPPush: All registration endpoints failed — will retry in 30s")
            CallFileLogger.shared.log("❌ VoIPPush: ALL registration endpoints FAILED — retry in 30s")

            // 📱 MULTI-DEVICE: Retry registration after 30 seconds
            try? await Task.sleep(nanoseconds: 30_000_000_000)
            await MainActor.run {
                if !self.isRegistered {
                    self.registerTokensWithVPS()
                }
            }
        }
    }
    
    /// Unregister device when user logs out
    func unregisterDevice() {
        guard let publicKey = UserDefaults.standard.string(forKey: "publicKey") else { return }
        guard let url = URL(string: "\(pushServiceURL)/unregister-device") else { return }

        // 🔧 MULTI-DEVICE: Include deviceId so server removes only THIS device
        let deviceId = Self.getDeviceId()
        let payload: [String: Any] = ["publicKey": publicKey, "deviceId": deviceId]
        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload) else { return }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = jsonData
        signRequest(&request)

        URLSession.shared.dataTask(with: request).resume()

        isRegistered = false
        OshiLog.call.info("📞 VoIPPush: Device unregistered")
    }
    
    // MARK: - Incoming Call Handling

    /// __LEAK_FIX_2026_09_25__ What senders put in `callerName` instead of the real name.
    static let neutralCallerName = "OSHI User"

    /// __LEAK_FIX_2026_09_25__ Display name for a caller, resolved on THIS device:
    /// saved alias / shared nickname, else a real name from an old sender's push, else a
    /// shortened key. Never empty, never "Unknown".
    static func resolveCallerDisplayName(callerKey: String, pushName: String?) -> String {
        if let alias = MessageManager.lookupContactDisplayName(callerKey) { return alias }
        if let n = pushName, !n.isEmpty, n != "Unknown", n != neutralCallerName { return n }
        return String(MessageManager.normalizeAliasKey(callerKey).prefix(12)) + "..."
    }
    
    /// Report incoming call to CallKit (called when VoIP push received)
    func reportIncomingCall(
        callerPublicKey: String,
        callerName: String,
        callId: String,
        isVideoCall: Bool = false,  // 🔧 FIX: Pass video call state
        completion: ((Error?) -> Void)? = nil
    ) {
        // 🔧 FIX: Resolve caller name — never show "Unknown" in CallKit or in-app UI
        // Priority: 1) Saved alias, 2) Real push name, 3) Shortened public key
        let resolvedName: String = {
            // __ALIAS_KEY_FOLD_2026_08_13__ This used to fold `-`/`_` but NOT the `=`
            // padding, so an alias saved as `…cyc=` never matched a caller key that
            // arrives from the relay as `…cyc`. One shared normalization now lives in
            // MessageManager.normalizeAliasKey — do not re-inline a second variant here.
            // __SHARED_NICKNAME_2026_09_22__ alias local, sinon pseudo partage.
            if let alias = MessageManager.lookupContactDisplayName(callerPublicKey) {
                return alias
            }
            // Use push callerName if it's a real name
            if !callerName.isEmpty && callerName != "Unknown" && callerName != "OSHI User" {
                return callerName
            }
            // Fall back to shortened public key (always identifiable)
            return String(MessageManager.normalizeAliasKey(callerPublicKey).prefix(12)) + "..."
        }()

        // 🇨🇳 China/bypass mode: Skip CallKit entirely, show in-app UI directly
        //
        // __BYPASS_NEEDS_NO_PUSHKIT_2026_10_01__ …but ONLY in a process that never set
        // CallKit/PushKit up (`init` skips both when the bypass is on at launch). If the
        // bypass flipped on AFTER launch (Settings › native calls, `callKitDisabled`), the
        // PushKit registry is live and this push came through it: returning without a
        // CallKit report is exactly what iOS kills with 0xBAADCA11 (iPhone 17, build 156,
        // 29/09 12:30, Natalia's call). Then the obligation is met with a report that is
        // ended at once (kept out of Recents), and the in-app UI still rings.
        if ChinaRegionDetector.shared.shouldBypassCallKit, callProvider != nil {
            CallFileLogger.shared.log("📞 VoIPPush: bypass on but PushKit is live in this process — reporting+ending for iOS, ringing in-app callId=\(callId.prefix(8))")
            satisfyPushObligation(callerPublicKey: callerPublicKey, displayName: nil,
                                  endReason: .answeredElsewhere, why: "bypass_after_launch",
                                  hideFromRecents: true) { completion?(nil) }
            setCallKitPresenting(nil, reason: "china_bypass")
            currentCallerPublicKey = callerPublicKey
            currentCallerName = resolvedName
            DispatchQueue.main.async {
                self.showInAppCallUI(callerPublicKey: callerPublicKey, callerName: resolvedName, callId: callId, isVideoCall: isVideoCall)
            }
            Task { await self.pollForCallSignal(from: callerPublicKey, callId: callId) }
            return
        }
        if ChinaRegionDetector.shared.shouldBypassCallKit {
            OshiLog.call.info("📞 Native mode (\(ChinaRegionDetector.shared.bypassReason)): Showing in-app incoming call UI")
            // CallKit is not in play at all here, so the in-app overlay is the ONLY
            // surface — leave the source of truth nil so the views draw it.
            setCallKitPresenting(nil, reason: "china_bypass")
            currentCallerPublicKey = callerPublicKey
            currentCallerName = resolvedName

            // Show in-app overlay immediately via setupIncomingCallFromPush
            DispatchQueue.main.async {
                self.showInAppCallUI(callerPublicKey: callerPublicKey, callerName: resolvedName, callId: callId, isVideoCall: isVideoCall)
            }

            // Poll for session key in background
            Task {
                await self.pollForCallSignal(from: callerPublicKey, callId: callId)
            }

            completion?(nil)
            return
        }

        // 🔧 IDEMPOTENCY GUARD (2026-09-15, "double notification to pick up"):
        // CallKit builds a brand-new incoming-call surface for every distinct UUID
        // we report. `reportIncomingCall` is the ONLY place that mints one, and it
        // used to do so unconditionally — trusting the caller to have deduped. But
        // there is a window it does NOT cover: while the app is in the BACKGROUND
        // (phone locked, the common case) `reportIncomingCall` does not call
        // `setupIncomingCallFromPush`, so `VoiceCallManager.callState` stays `.idle`
        // even though CallKit is already ringing. The `.ringing`/`.inCall`
        // coalescing guards in `didReceiveIncomingPushWith` (which route extra
        // pushes through `satisfyPushObligation`) therefore never engage, and the
        // per-callId `processedCallIds` set only catches a push with the SAME
        // callId. So a second push for the SAME logical call carrying a DIFFERENT
        // callId (a caller retry / reconnection, or the caller shotgunning the
        // callRequest across P2P+WS+relay+HTTP and the server pushing more than
        // once) fell straight through to here and reported a SECOND fresh UUID —
        // two stacked "pick up" surfaces. Answering one then tears down the other,
        // which is the "the first call ended when I tried to answer" report.
        //
        // `callKitPresentingCallId` is the single source of truth for "an INCOMING
        // CallKit surface is live"; it is set only around this report (report_pending
        // / report_ok) and never for an outgoing call, and it is cleared on
        // end / answer / provider-reset / report-failure. When it is non-nil we are
        // already presenting an incoming call, so re-report the LIVE UUID instead of
        // minting a new one: iOS recognises the call it already has
        // (`callUUIDAlreadyExists`) and builds NO second surface. We still poll for
        // the session key on this carrier so audio can set up if this delivery wins.
        // The China/bypass branch above returns before this point and never sets
        // `callKitPresentingCallId`, so it is unaffected.
        // __PUSHKIT_OFF_MAIN_2026_09_23__ this may run on `pushQueue` or on main, so
        // "is a surface live? if not, mint one" is ONE atomic step under the lock —
        // otherwise two callers on two queues could both see nil and both mint.
        var staleUUID: UUID?
        let claim: (uuid: UUID, isNew: Bool, presenting: String?) = withCallState {
            if let presenting = _presentingCallIdSync, let live = _currentCallUUID {
                // __STALE_LIVE_UUID_2026_09_25__ only coalesce onto a call iOS still has.
                if callKitStillHas(live) { return (live, false, presenting) }
                staleUUID = live
            }
            let fresh = UUID()
            _currentCallUUID = fresh
            currentUUIDMintedAt = Date()
            _currentCallerPublicKey = callerPublicKey
            _currentCallerName = resolvedName
            _presentingCallIdSync = callId  // claimed; setCallKitPresenting below publishes it
            return (fresh, true, nil)
        }
        if let staleUUID {
            CallFileLogger.shared.log("📞 CallKit: STALE live UUID \(staleUUID.uuidString.prefix(8)) — iOS no longer has that call; reporting callId=\(callId.prefix(8)) as a NEW call")
        }
        if !claim.isNew {
            let liveUUID = claim.uuid
            CallFileLogger.shared.log("📞 CallKit: reportIncomingCall — incoming surface already live (UUID \(liveUUID.uuidString.prefix(8)), presenting callId=\(claim.presenting?.prefix(8) ?? "nil")); coalescing callId=\(callId.prefix(8)) — NO second surface")
            let sameCallUpdate = CXCallUpdate()
            sameCallUpdate.remoteHandle = CXHandle(type: .generic, value: MessageManager.resolveContactAlias(callerPublicKey))
            sameCallUpdate.localizedCallerName = resolvedName
            reportNewIncomingCallFromAnyQueue(liveUUID, sameCallUpdate) { [weak self] error in
                Task { [weak self] in
                    await self?.pollForCallSignal(from: callerPublicKey, callId: callId)
                }
                completion?(error)
            }
            return
        }

        let uuid = claim.uuid

        // Create call update
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: MessageManager.resolveContactAlias(callerPublicKey))
        update.localizedCallerName = resolvedName
        update.hasVideo = isVideoCall  // 🔧 FIX: Properly indicate video calls to iOS
        currentCallHasVideo = isVideoCall  // baseline for updateCallHasVideo(_:)
        update.supportsGrouping = false
        update.supportsUngrouping = false
        update.supportsHolding = false
        update.supportsDTMF = false

        // 🔧 FIX: Check app state BEFORE reporting to CallKit
        // __PUSHKIT_OFF_MAIN_2026_09_23__ `UIApplication.applicationState` is main-only;
        // off main we read the gate's mirror (kept by UIApplication notifications).
        let isAppActive = Thread.isMainThread
            ? UIApplication.shared.applicationState == .active
            : CallPushGate.shared.appState == .active
        let isVideoCall = isVideoCall

        OshiLog.call.info("📞 CallKit: Reporting incoming call from \(resolvedName)")
        OshiLog.call.info("   UUID: \(uuid)")
        OshiLog.call.info("   Caller: \(callerPublicKey.prefix(12))...")
        CallFileLogger.shared.log("📞 CallKit: reporting incoming call from=\(resolvedName) isVideo=\(isVideoCall) appActive=\(isAppActive)")

        // 🔧 FIX (2026-07-27): claim the incoming surface for CallKit BEFORE any
        // call state is published. `setupIncomingCallFromPush` below flips
        // VoiceCallManager to .ringing, which is what ContentView watches; if the
        // claim landed after it, SwiftUI would render one frame of the in-app
        // overlay before hiding it again — the "second surface flashing".
        // Both writes happen in the same main-thread turn, so the view only ever
        // observes the settled combination.
        setCallKitPresenting(callId, reason: "report_pending")

        if Thread.isMainThread {
            if isAppActive {
                // FOREGROUND: this is call SETUP, not UI. setupIncomingCallFromPush
                // publishes the incoming call and prewarms P2P/ICE at push arrival
                // (worth seconds of connect time). It no longer implies a visible
                // overlay: while `callKitPresentingCallId` is set, CallKit's banner is
                // the one and only incoming surface and every view stands down.
                showInAppCallUI(callerPublicKey: callerPublicKey, callerName: resolvedName, callId: callId, isVideoCall: isVideoCall)
            }
        } else {
            // __PUSHKIT_OFF_MAIN_2026_09_23__ off main (PushKit queue): VoiceCallManager
            // is main-thread state, so the setup is queued BEHIND the surface claim
            // published above (same FIFO), and the report below does not wait for it.
            DispatchQueue.main.async { [weak self] in
                guard UIApplication.shared.applicationState == .active else { return }
                self?.showInAppCallUI(callerPublicKey: callerPublicKey, callerName: resolvedName, callId: callId, isVideoCall: isVideoCall)
            }
        }

        restoreRecentsForRealCall()   // __BLOCKED_NO_RECENTS_2026_09_24__
        reportNewIncomingCallFromAnyQueue(uuid, update) { [weak self] error in
            if let error = error {
                OshiLog.call.info("❌ CallKit: Failed to report call: \(error.localizedDescription)")
                let nsError = error as NSError
                let filtered = VoIPPushManager.isCallKitFilterError(nsError)
                CallFileLogger.shared.log("❌ CallKit: report FAILED — in-app overlay takes over: \(error.localizedDescription) (domain=\(nsError.domain) code=\(nsError.code) filtered=\(filtered))")
                // 🔧 FIX (2026-07-28, DND): a FILTERED report means CallKit will never
                // own this call — and therefore never call provider(_:didActivate:).
                // Say so once, here, so the audio path stops waiting for a handover
                // that cannot arrive (see VoiceCallManager.callKitWillNotOwnAudio).
                if filtered {
                    self?.noteCallKitWillNotPresent(why: "report_failed", code: nsError.code)
                }
                // CallKit is NOT presenting anything, so hand the surface back to the
                // in-app overlay — otherwise the call rings with no UI at all.
                self?.setCallKitPresenting(nil, reason: filtered ? "report_filtered" : "report_failed")
                DispatchQueue.main.async { [weak self] in
                    // 🔧 FIX (2026-09-15, DOUBLE VIDEO-CALL NOTIFICATION under Focus):
                    // do NOT nil `currentCallUUID` when the report was FILTERED.
                    // Measured on the iPhone 17 (i17_b136.log 00:02:23): Focus/DND
                    // filtered the real report, this branch cleared the UUID, and the
                    // NEXT (duplicate) push then had no live UUID to coalesce onto — so
                    // `satisfyPushObligation` fell to a FRESH-UUID throwaway placeholder,
                    // which iOS presents by breaking DND for a repeated caller. That is
                    // the banner the user saw stacked on the in-app overlay (two
                    // incoming surfaces, both with answer/decline). By RETAINING the
                    // UUID, the duplicate push re-reports the SAME uuid, which iOS
                    // filters again (or returns callUUIDAlreadyExists) → obligation met,
                    // NO second surface. The in-app overlay stays the single surface
                    // (callKitPresentingCallId is nil, so the owner is .inApp), and the
                    // UUID is still cleared on the normal end paths (endCall, cleanup).
                    // Only a genuine hard failure (not a filter) clears it.
                    if !filtered { self?.currentCallUUID = nil }
                    // No CallKit ring means no CallKit ringtone either, and mesh
                    // call signals must stop being discarded as "CallKit has it".
                    // Both hang off this flag, which handleVoIPPushActive raises for
                    // every push before iOS has confirmed anything.
                    VoiceCallManager.shared.isCallKitHandlingIncoming = false
                    self?.showInAppCallUI(callerPublicKey: callerPublicKey, callerName: resolvedName, callId: callId, isVideoCall: isVideoCall)
                }
            } else {
                OshiLog.call.info("✅ CallKit: Incoming call reported (appActive: \(isAppActive))")
                CallFileLogger.shared.log("📞 CallKit: OWNS incoming surface callId=\(callId.prefix(8)) appActive=\(isAppActive)")
                // 🔧 FIX (2026-07-27): the foreground branch used to immediately
                // reportCall(endedAt:.answeredElsewhere) to kill CallKit's banner so
                // our overlay could be the visible UI. Two consequences, both bad:
                //   1. the teardown races the banner's own dismiss animation, so for
                //      a beat BOTH surfaces are on screen — the reported bug;
                //   2. it makes the app own the audio session. The callee then
                //      accepted in-app (wasAnsweredViaCallKit=false), CallKit never
                //      called didActivate, and setActive failed 561017449 twice
                //      before the watchdog failed too (2003329396) → no audio.
                // CallKit reported the call; CallKit keeps it, rings it, and owns its
                // audio session until the user answers or it ends.
                self?.setCallKitPresenting(callId, reason: "report_ok")
            }

            Task { [weak self] in
                await self?.pollForCallSignal(from: callerPublicKey, callId: callId)
            }

            completion?(error)
        }
    }
    
    /// Show in-app incoming call UI immediately (without waiting for signal poll)
    /// Uses VoiceCallManager.setupIncomingCallFromPush so IncomingCallOverlay appears instantly
    private func showInAppCallUI(callerPublicKey: String, callerName: String, callId: String, isVideoCall: Bool) {
        VoiceCallManager.shared.setupIncomingCallFromPush(
            callerPublicKey: callerPublicKey,
            callerName: callerName,
            callId: callId,
            isVideoCall: isVideoCall
        )
    }

    /// 🔧 FIX (2026-07-27): `showIncomingCallWindow` / `dismissIncomingCallWindow`
    /// deleted here. They built a THIRD IncomingCallOverlayView inside a private
    /// UIWindow at `.alert + 1` whose stated purpose was "guarantee our overlay is
    /// always on top, making the CallKit banner invisible". Nothing called them, but
    /// the rule they encoded is the inverse of the one the app now follows, so they
    /// were left as a trap for the next reader. When CallKit is presenting, CallKit
    /// is the surface — see `callKitPresentingCallId`.

    /// 🔧 Poll VPS for the encrypted call signal (contains session key)
    /// Retries every 500ms for up to 10 seconds to handle timing races
    private func pollForCallSignal(from callerPublicKey: String, callId: String) async {
        guard let myPublicKey = UserDefaults.standard.string(forKey: "publicKey"),
              !myPublicKey.isEmpty else {
            OshiLog.call.info("❌ VoIPPush: No public key for polling")
            return
        }

        // 🔧 CRITICAL FIX: Use base64url encoding (not percent encoding)
        let encodedKey = base64urlEncode(myPublicKey)

        // Try nginx proxy first, then fallback to direct port
        let urls = [
            "\(callServiceURL)/signals/\(encodedKey)",      // /api/call/signals via nginx
            "\(callServiceDirectURL)/signals/\(encodedKey)" // :8083/signals direct
        ]

        let normalizedCallerKey = normalizeKey(callerPublicKey)

        OshiLog.call.info("📞 VoIPPush: Polling for call signal from \(callerPublicKey.prefix(12))... (retry loop)")

        // 🔧 CRITICAL FIX: Retry polling — signal may not be posted yet when VoIP push arrives
        // The caller sends VoIP push and signal roughly simultaneously, but the push can
        // arrive before the signal is stored on the server.
        let maxAttempts = 20  // 20 × 500ms = 10 seconds
        for attempt in 1...maxAttempts {
            for urlString in urls {
                guard let url = URL(string: urlString) else { continue }

                var request = URLRequest(url: url)
                request.httpMethod = "GET"
                request.timeoutInterval = 5
                signRequest(&request)

                do {
                    let (data, response) = try await URLSession.shared.data(for: request)

                    guard let httpResponse = response as? HTTPURLResponse,
                          httpResponse.statusCode == 200 else {
                        continue
                    }

                    if let signals = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] {
                        for signal in signals {
                            if let sender = signal["sender"] as? String,
                               normalizeKey(sender) == normalizedCallerKey,
                               let signalData = signal["signal"] as? String,
                               let encryptedData = Data(base64Encoded: signalData) {

                                OshiLog.call.info("✅ VoIPPush: Found call signal from caller! (attempt \(attempt))")
                                CallFileLogger.shared.log("✅ VoIPPush: pollForCallSignal found signal (attempt \(attempt), \(encryptedData.count) bytes)")

                                // Store for when call is answered
                                pendingCallData?["encryptedSignal"] = signalData
                                pendingCallData?["encryptedData"] = encryptedData

                                // 🔧 CRITICAL FIX (silent calls / no audio TX):
                                // Earlier this dedup keyed on the caller's pubkey, which made
                                // `hasNotifiedCallSignal` sticky across calls. Once a peer had been
                                // notified once, EVERY subsequent call from that peer was silently
                                // dropped here (no `ReceivedCallSignalFromIPFS` posted →
                                // handleIPFSCallSignal never ran → sessionKey never installed →
                                // encryption failed on every outgoing audio frame).
                                // Key the dedup on callId instead: each call has a unique id, so
                                // we still squash same-call retries from poll vs push, but a new
                                // call from the same peer can install its own session key.
                                if hasNotifiedCallSignal && lastNotifiedCallerId == callId {
                                    OshiLog.call.info("⚠️ VoIPPush: Already notified for this callId, skipping duplicate")
                                    return
                                }

                                hasNotifiedCallSignal = true
                                lastNotifiedCallerId = callId

                                // Notify VoiceCallManager about the signal
                                DispatchQueue.main.async {
                                    NotificationCenter.default.post(
                                        name: NSNotification.Name("ReceivedCallSignalFromIPFS"),
                                        object: nil,
                                        userInfo: [
                                            "senderPublicKey": callerPublicKey,
                                            "encryptedData": encryptedData
                                        ]
                                    )
                                }
                                return
                            }
                        }
                    }

                    // Got 200 but no matching signal — wait and retry
                    break  // Break out of URL loop to retry with delay

                } catch {
                    OshiLog.call.info("⚠️ VoIPPush: Poll failed via \(urlString) (attempt \(attempt)): \(error.localizedDescription)")
                }
            }

            // Wait 500ms before next attempt
            if attempt < maxAttempts {
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
        }

        OshiLog.call.info("⚠️ VoIPPush: No signal found after \(maxAttempts) attempts (VoiceCallManager 1s polling will continue)")
        CallFileLogger.shared.log("⚠️ VoIPPush: pollForCallSignal FAILED after \(maxAttempts) attempts")
    }
    
    /// Report incoming call answered by our in-app overlay.
    /// We dismiss the CallKit call so our app fully owns the audio session —
    /// CXAnswerCallAction via CXCallController is the "FaceTime-like" API but it
    /// doesn't work for programmatic self-answer: the request silently never
    /// completes, didActivate never fires, and the mic gets a phantom dead
    /// session (audioLevelRMS=0). reportCall(endedAt:reason:.answeredElsewhere)
    /// releases CallKit's hold so our engine can truly activate the session.
    ///
    /// 🔧 FIX (2026-07-27): call this ONLY for an accept that did not come from
    /// CallKit — the China bypass, a `reportNewIncomingCall` that failed, or an
    /// auto-accept. On those paths CallKit never received a CXAnswerCallAction, so it
    /// will never call didActivate, and the app genuinely has to take the session.
    /// The CallKit-answer path must NOT reach here: VoiceCallManager gates the call
    /// on `wasAnsweredViaCallKit`, and this teardown is exactly what left the callee
    /// fighting CallKit for the audio session (setActive 561017449) in the 08:47 logs.
    /// Releasing the surface flag first keeps the in-app UI usable from here on.
    func reportIncomingCallHandledByApp() {
        CallFileLogger.shared.log("📞 CallKit: reportIncomingCallHandledByApp() called")
        if ChinaRegionDetector.shared.shouldBypassCallKit {
            currentCallerPublicKey = nil
            currentCallerName = nil
            CallFileLogger.shared.log("📞 CallKit: China bypass — skipping dismiss")
            return
        }
        guard let uuid = currentCallUUID else {
            CallFileLogger.shared.log("📞 CallKit: No UUID to dismiss (already nil)")
            return
        }
        setCallKitPresenting(nil, reason: "handled_by_app")
        callProvider?.reportCall(with: uuid, endedAt: Date(), reason: .answeredElsewhere)
        currentCallUUID = nil
        CallFileLogger.shared.log("📞 CallKit: Dismissed (reportCall endedAt=answeredElsewhere)")
    }

    // MARK: - Mid-call video upgrade

    /// Last `hasVideo` value CallKit was told about, so a repeated upgrade signal
    /// doesn't produce redundant provider traffic.
    private var currentCallHasVideo: Bool = false

    /// Tell CallKit that the live call became (or stopped being) a video call.
    ///
    /// 🔧 FIX: `CXCallUpdate.hasVideo` was set exactly ONCE, at
    /// `reportNewIncomingCall` time, and never touched again. A voice call upgraded
    /// to video mid-call therefore stayed an audio call as far as iOS was concerned:
    /// the native call UI keeps the audio-call layout (no video controls, no
    /// "video call" label), the lock-screen / Dynamic Island treatment is wrong, and
    /// the system's own camera-in-use affordances never appear. CallKit exposes
    /// exactly one way to correct this after the fact — post a fresh CXCallUpdate for
    /// the SAME UUID with `reportCall(with:updated:)`.
    ///
    /// Call sites live in VoiceCallManager's video-upgrade accept/revert paths.
    func updateCallHasVideo(_ hasVideo: Bool) {
        // CallKit bypassed (China): there is no provider to inform.
        if ChinaRegionDetector.shared.shouldBypassCallKit { return }

        guard let uuid = currentCallUUID else {
            CallFileLogger.shared.log("📞 CallKit: updateCallHasVideo(\(hasVideo)) — no live UUID, ignoring")
            return
        }
        guard hasVideo != currentCallHasVideo else { return }
        currentCallHasVideo = hasVideo

        let update = CXCallUpdate()
        update.hasVideo = hasVideo
        // Only the fields we set are applied; the rest of the call's metadata
        // (handle, caller name, supports* flags) is left untouched by CallKit.
        callProvider?.reportCall(with: uuid, updated: update)
        OshiLog.call.info("📞 CallKit: call updated hasVideo=\(hasVideo)")
        CallFileLogger.shared.log("📞 CallKit: reportCall(updated:) hasVideo=\(hasVideo) uuid=\(uuid.uuidString.prefix(8))")
    }

    /// End call (from our side)
    func endCall(reason: CXCallEndedReason = .remoteEnded) {
        // Whatever happens below, CallKit is no longer ringing this call — release
        // the incoming surface so the in-app UI is free again for the next call.
        setCallKitPresenting(nil, reason: "end_call")

        // CallKit bypassed: No CallKit, just reset state
        if ChinaRegionDetector.shared.shouldBypassCallKit {
            currentCallerPublicKey = nil
            currentCallerName = nil
            hasNotifiedCallSignal = false
            lastNotifiedCallerId = nil
            pendingCallData = nil
            DispatchQueue.main.asyncAfter(deadline: .now() + 5.0) { [weak self] in
                self?.processedCallIds.removeAll()
                self?.lastProcessedCallTime = nil
            }
            OshiLog.call.info("📞 Native mode: Call ended (no CallKit)")
            return
        }

        guard let uuid = currentCallUUID else { return }

        callProvider?.reportCall(with: uuid, endedAt: Date(), reason: reason)
        currentCallUUID = nil
        currentCallerPublicKey = nil
        currentCallerName = nil
        currentCallHasVideo = false  // next call re-baselines at report time

        // 🔧 FIX: Reset deduplication flags for next call
        hasNotifiedCallSignal = false
        lastNotifiedCallerId = nil
        pendingCallData = nil

        // 🔧 FIX: Clear processed call IDs after call ends (with delay to catch late duplicates)
        DispatchQueue.main.asyncAfter(deadline: .now() + 5.0) { [weak self] in
            self?.processedCallIds.removeAll()
            self?.lastProcessedCallTime = nil
        }

        OshiLog.call.info("📞 CallKit: Call ended - \(reason)")
    }
    
    /// Start outgoing call (for CallKit integration)
    func startOutgoingCall(to peerPublicKey: String, peerName: String, isVideoCall: Bool = false) {
        // CallKit bypassed: No CallKit for outgoing calls
        if ChinaRegionDetector.shared.shouldBypassCallKit {
            currentCallerPublicKey = peerPublicKey
            currentCallerName = peerName
            OshiLog.call.info("📞 Native mode: Outgoing call started (no CallKit)")
            return
        }

        let uuid = UUID()
        currentCallUUID = uuid
        currentCallerPublicKey = peerPublicKey
        currentCallerName = peerName

        // 🔧 FIX (2026-08-07): the handle is what iOS writes into the
        // system call history, so a raw public key made the whole list
        // unreadable — every entry an indistinguishable base64 blob. The
        // alias is what the owner actually named this person.
        let handle = CXHandle(type: .generic, value: MessageManager.resolveContactAlias(peerPublicKey))
        let startCallAction = CXStartCallAction(call: uuid, handle: handle)
        startCallAction.isVideo = isVideoCall  // 🔧 FIX: Pass actual video state
        currentCallHasVideo = isVideoCall  // baseline for updateCallHasVideo(_:)
        startCallAction.contactIdentifier = peerName

        let transaction = CXTransaction(action: startCallAction)

        restoreRecentsForRealCall()   // __BLOCKED_NO_RECENTS_2026_09_24__
        callController?.request(transaction) { error in
            if let error = error {
                OshiLog.call.info("❌ CallKit: Failed to start outgoing call: \(error.localizedDescription)")
            } else {
                OshiLog.call.info("✅ CallKit: Outgoing call started")

                // Update call to connecting state
                self.callProvider?.reportOutgoingCall(with: uuid, startedConnectingAt: Date())
            }
        }
    }
    
    /// Report outgoing call connected
    func reportOutgoingCallConnected() {
        // CallKit bypassed: No CallKit to report to
        if ChinaRegionDetector.shared.shouldBypassCallKit {
            OshiLog.call.info("📞 Native mode: Outgoing call connected (no CallKit)")
            return
        }

        guard let uuid = currentCallUUID else { return }
        callProvider?.reportOutgoingCall(with: uuid, connectedAt: Date())
        OshiLog.call.info("✅ CallKit: Outgoing call connected")
    }
    
    // MARK: - App Lifecycle
    
    @objc private func handleAppDidBecomeActive() {
        // Handle any pending call that came in via push
        if let pendingCall = pendingCallData {
            OshiLog.call.info("📞 VoIPPush: Processing pending call...")
            processPendingCall(pendingCall)
            pendingCallData = nil
        }
    }
    
    // REMOVED: handleCallKitAnswer - now handled only by VoiceCallManager to prevent duplicate accept

    /// Get pending call data for VoiceCallManager to use
    func getPendingCallData() -> [String: Any]? {
        return pendingCallData
    }

    /// Clear pending call data after it's been processed
    func clearPendingCallData() {
        pendingCallData = nil
    }
    
    private func processPendingCall(_ data: [String: Any]) {
        // Validate required fields exist
        // __LEAK_FIX_2026_09_25__ `callerName` is no longer required (senders stop
        // shipping the real name); fill it locally so downstream observers keep working.
        guard let callerKey = data["callerPublicKey"] as? String else { return }
        var data = data
        if (data["callerName"] as? String)?.isEmpty ?? true {
            data["callerName"] = Self.resolveCallerDisplayName(callerKey: callerKey, pushName: nil)
        }
        let callId = data["callId"] as? String ?? ""

        // 🔧 CRITICAL FIX (silent calls / no audio TX): same fix as in
        // pollForCallSignal — dedup by callId, not by caller pubkey, so a new
        // call from the same peer is not silently dropped when an old call's
        // dedup flag is still sticky in memory.
        if hasNotifiedCallSignal && lastNotifiedCallerId == callId {
            OshiLog.call.info("⚠️ VoIPPush: processPendingCall - already notified for this callId, skipping duplicate")
            return
        }

        // Notify VoiceCallManager about the incoming call
        NotificationCenter.default.post(
            name: NSNotification.Name("VoIPCallReceived"),
            object: nil,
            userInfo: data
        )

        // If we have encrypted signal data, also send it
        if let encryptedData = data["encryptedData"] as? Data {
            hasNotifiedCallSignal = true
            lastNotifiedCallerId = callId

            NotificationCenter.default.post(
                name: NSNotification.Name("ReceivedCallSignalFromIPFS"),
                object: nil,
                userInfo: [
                    "senderPublicKey": callerKey,
                    "encryptedData": encryptedData
                ]
            )
        }
    }
}

// MARK: - PKPushRegistryDelegate

extension VoIPPushManager: PKPushRegistryDelegate {
    
    func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        guard type == .voIP else { return }

        // Convert token to hex string
        let token = pushCredentials.token.map { String(format: "%02.2hhx", $0) }.joined()

        DispatchQueue.main.async {
            self.voipToken = token
            OshiLog.call.info("📞 VoIPPush: Token received")
            OshiLog.call.info("   Token: \(token.prefix(32))...")
            CallFileLogger.shared.log("📞 VoIPPush: VoIP token received (\(token.prefix(16))...)")

            // 🔧 FIX: Don't register immediately - wait for APNs token to arrive
            // APNs token typically arrives within 1-2 seconds of VoIP token
            // If we register now with empty APNs, the VPS stores null and the
            // second registration (with APNs) might arrive out of order
            if self.apnsToken != nil {
                // Already have APNs token, register immediately with both
                OshiLog.call.info("📞 VoIPPush: APNs token already available, registering both")
                CallFileLogger.shared.log("📞 VoIPPush: APNs token already available, registering both tokens")
                self.registerTokensWithVPS()
            } else {
                // Schedule delayed registration - give APNs 3 seconds to arrive
                OshiLog.call.info("📞 VoIPPush: Waiting for APNs token before registering...")
                CallFileLogger.shared.log("📞 VoIPPush: Waiting 3s for APNs token before registering...")
                self.registrationWorkItem?.cancel()
                let workItem = DispatchWorkItem { [weak self] in
                    guard let self = self else { return }
                    if self.apnsToken == nil {
                        OshiLog.call.info("⏰ VoIPPush: APNs token not received after 3s, registering with VoIP only")
                        CallFileLogger.shared.log("⏰ VoIPPush: APNs token timeout (3s), registering with VoIP only")
                    }
                    self.registerTokensWithVPS()
                }
                self.registrationWorkItem = workItem
                DispatchQueue.main.asyncAfter(deadline: .now() + 3.0, execute: workItem)
            }
        }
    }
    
    /// 🔧 FIX (2026-07-27b) — escape hatch, default OFF (= new behaviour).
    ///
    /// Set `callkit_placeholder_for_extra_push` to true in UserDefaults to restore the
    /// shipped behaviour where a push we are NOT going to ring for is answered with a
    /// brand-new placeholder CXCall. That placeholder is what iOS drew as a second
    /// incoming-call surface, so it is off by default; the flag exists only so the
    /// old path can be restored on a device without a rebuild if the coalescing report
    /// ever upsets PushKit in the field.
    private var usePlaceholderForExtraPush: Bool {
        UserDefaults.standard.bool(forKey: "callkit_placeholder_for_extra_push")
    }

    /// 🔧 FIX (2026-07-28) — CallKit's "iOS will NOT show this call" family.
    ///
    /// MECHANISM: `reportNewIncomingCall` completes with domain
    /// `CXErrorDomainIncomingCall` and one of these codes when the system has already
    /// DECIDED not to present the call. Verified verbatim against
    /// `iPhoneOS26.2.sdk/System/Library/Frameworks/CallKit.framework/Headers/CXError.h`:
    ///   2 = callUUIDAlreadyExists              (NOT a filter — handled separately)
    ///   3 = filteredByDoNotDisturb             ← measured on the iPhone 14, 22:50:28Z
    ///   4 = filteredByBlockList
    ///   5 = filteredDuringRestrictedSharingMode
    ///   7 = filteredBySensitiveParticipants
    ///
    /// The distinction that matters: these are **terminal for the report and not about
    /// the UUID**. Re-reporting a DIFFERENT UUID cannot succeed either, because the
    /// filter — not the identifier — is what rejected us; it will reject the
    /// replacement for exactly the same reason. So falling back to a throwaway
    /// placeholder here is pure harm: it cannot ring, and on 2026-07-27 it rebuilt the
    /// very second surface `2e6b098` was written to eliminate, stacked on top of the
    /// in-app overlay (`📞 SURFACE +callkit(placeholder) uuid=5D539309 reason=duplicate_push`).
    ///
    /// PushKit obligation — the reason it is safe to stop here. `PKPushRegistry.h`
    /// (iPhoneOS26.2.sdk) states the obligation as *calling* the report, and names this
    /// exact outcome as an expected one: "When you call that method, the system displays
    /// the standard incoming call interface to the user **unless an error occurs. For
    /// example, the system reports an error if the user enabled Do Not Disturb.**" The
    /// obligation is discharged by the report, not by its success. `CXProvider.h:93`
    /// agrees from the other side: "If completion is invoked with a non-nil `error`, the
    /// incoming call has been disallowed by the system and will not be displayed".
    private static func isCallKitFilterError(_ error: NSError?) -> Bool {
        guard let error, error.domain == CXErrorDomainIncomingCall else { return false }
        switch error.code {
        case CXErrorCodeIncomingCallError.filteredByDoNotDisturb.rawValue,
             CXErrorCodeIncomingCallError.filteredByBlockList.rawValue,
             CXErrorCodeIncomingCallError.filteredDuringRestrictedSharingMode.rawValue,
             CXErrorCodeIncomingCallError.filteredBySensitiveParticipants.rawValue:
            return true
        default:
            return false
        }
    }

    /// Single funnel for "iOS filtered our report". Two consequences, both of which
    /// were previously left implicit and cost a whole two-device run to diagnose:
    ///   1. it goes in `call_debug.log` with the numeric code, so the next reader does
    ///      not have to translate a localised `NSError` description by hand;
    ///   2. it tells `VoiceCallManager` that `provider(_:didActivate:)` is NEVER
    ///      coming for this call, which is what unblocks the audio session (I1).
    private func noteCallKitWillNotPresent(why: String, code: Int) {
        let name: String
        switch code {
        case CXErrorCodeIncomingCallError.filteredByDoNotDisturb.rawValue: name = "doNotDisturb"
        case CXErrorCodeIncomingCallError.filteredByBlockList.rawValue: name = "blockList"
        case CXErrorCodeIncomingCallError.filteredDuringRestrictedSharingMode.rawValue: name = "restrictedSharing"
        case CXErrorCodeIncomingCallError.filteredBySensitiveParticipants.rawValue: name = "sensitiveParticipants"
        default: name = "code\(code)"
        }
        CallFileLogger.shared.log("📞 CallKit: FILTERED (\(name)) via \(why) — CallKit will not present or activate audio for this call")
        VoiceCallManager.shared.callKitWillNotOwnAudio(reason: "filtered:\(name)")
    }

    /// The ONLY way to satisfy PushKit's "you must report a call for every VoIP push"
    /// obligation for a push we are deliberately not going to ring for.
    ///
    /// MECHANISM: iOS terminates an app that returns from
    /// `didReceiveIncomingPushWith` without having called `reportNewIncomingCall`, so
    /// every early-return path has to report SOMETHING. Reporting a fresh UUID creates
    /// a second, fully-branded incoming-call surface (caller name + green/red buttons)
    /// — the bug. Re-reporting the UUID CallKit is already presenting is, to iOS, the
    /// call it already knows about: the report is made, and no new surface can be
    /// built. It completes with `CXErrorCodeIncomingCallError.callUUIDAlreadyExists`;
    /// if it fails for any OTHER reason we fall back to the placeholder, so the
    /// obligation is never left unmet.
    ///
    /// 🔧 FIX (2026-07-28): "any OTHER reason" was too wide. It swallowed the
    /// FILTERED family (`isCallKitFilterError`), where a placeholder is both useless
    /// and harmful — see that helper for the mechanism and for the SDK quotes that
    /// establish the obligation is discharged by the report, not by its success.
    ///
    /// When there is no live CallKit call, a placeholder is harmless (nothing else is
    /// on screen) and is still the path taken.
    private func satisfyPushObligation(callerPublicKey: String,
                                       displayName: String?,
                                       endReason: CXCallEndedReason,
                                       why: String,
                                       hideFromRecents: Bool = false,
                                       completion: @escaping () -> Void) {
        // __STALE_LIVE_UUID_2026_09_25__ a UUID iOS has already ended is not a live call:
        // re-reporting it would leave this push with no new report. Drop the stale
        // mirror and fall through to a throwaway report (a genuine new call, ended at once).
        if let stale = currentCallUUID, !callKitStillHas(stale) {
            CallFileLogger.shared.log("📞 CallKit: \(why) — STALE live UUID \(stale.uuidString.prefix(8)) (iOS has no such call) — throwaway report instead")
            withCallState {
                if _currentCallUUID == stale { _currentCallUUID = nil; _presentingCallIdSync = nil }
            }
        }
        if let liveUUID = currentCallUUID, !usePlaceholderForExtraPush {
            CallFileLogger.shared.log("📞 CallKit: \(why) — coalescing push onto live UUID \(liveUUID.uuidString.prefix(8)) (no second surface)")
            let sameCallUpdate = CXCallUpdate()
            sameCallUpdate.remoteHandle = CXHandle(type: .generic, value: MessageManager.resolveContactAlias(callerPublicKey))
            if let displayName { sameCallUpdate.localizedCallerName = displayName }
            reportNewIncomingCallFromAnyQueue(liveUUID, sameCallUpdate) { [weak self] error in
                let nsError = error as NSError?
                let alreadyExists = nsError?.domain == CXErrorDomainIncomingCall
                    && nsError?.code == CXErrorCodeIncomingCallError.callUUIDAlreadyExists.rawValue
                if error == nil || alreadyExists {
                    CallFileLogger.shared.log("📞 CallKit: \(why) — push obligation met on the live call (alreadyExists=\(alreadyExists))")
                    completion()
                } else if VoIPPushManager.isCallKitFilterError(nsError) {
                    // 🔧 FIX (2026-07-28, DND): iOS refused to PRESENT the call
                    // (Focus/DND, block list, restricted sharing, sensitive
                    // participants). We DID call reportNewIncomingCall, which is the
                    // whole of PushKit's obligation, so finish the push here. A
                    // placeholder would be filtered for the identical reason — it
                    // cannot ring — while still creating the extra surface.
                    CallFileLogger.shared.log("📞 CallKit: \(why) — report FILTERED by iOS (code=\(nsError?.code ?? -1)) — obligation met, NO placeholder")
                    self?.noteCallKitWillNotPresent(why: why, code: nsError?.code ?? -1)
                    completion()
                } else {
                    // Something other than "we already know this call" — we cannot
                    // claim the obligation is met, so report a placeholder after all.
                    CallFileLogger.shared.log("⚠️ CallKit: \(why) — same-UUID report failed (\(nsError?.localizedDescription ?? "?")), falling back to placeholder")
                    guard let self else {
                        // Cannot report anything any more; call completion() or iOS
                        // kills the app for never finishing the push.
                        completion()
                        return
                    }
                    self.reportThrowawayCall(callerPublicKey: callerPublicKey, displayName: displayName, endReason: endReason, why: why,
                                             hideFromRecents: hideFromRecents, completion: completion)
                }
            }
            return
        }
        reportThrowawayCall(callerPublicKey: callerPublicKey, displayName: displayName, endReason: endReason, why: why,
                            hideFromRecents: hideFromRecents, completion: completion)
    }

    // MARK: - __BLOCKED_NO_RECENTS_2026_09_24__ no Recents line for a blocked caller
    //
    // Owner decision (2026-09-24): a blocked contact leaves NO missed-call trace. PushKit
    // still forces a CallKit report for every VoIP push (above), and a report that is then
    // ended is written into the system Recents — `.unanswered` as a MISSED call — because
    // the provider is configured with `includesCallsInRecents = true` (so real OSHI calls
    // are listed there, which the owner wants to keep).
    //
    // CallKit has no per-call "keep out of Recents" flag; the switch lives on the provider
    // configuration, which is settable at any time. So, for the blocked throwaway ONLY, the
    // configuration is flipped to `includesCallsInRecents = false` just before the report and
    // flipped back once the throwaway is over (2 s later) — and immediately, whatever is
    // pending, before any REAL call is reported or started, so a real call can never be the
    // one left out. The throwaway is also ended as `.declinedElsewhere`, not `.unanswered`:
    // should a given iOS version record the call anyway, it is not flagged as missed.
    // Everything here is lock-guarded and main-free (0xBAADCA11 rule, runs on pushQueue).
    //
    // NOT VERIFIABLE ON THE SIMULATOR (no system Recents there): measured behaviour on a
    // device is the only proof. The primary fix is upstream: the caller's client stops
    // calling someone who told it "blocked" (BlockedByPeerStore), so this path only runs for
    // the first call after a block, before the notice has reached the caller.
    // (stored state `recentsLock` / `recentsHiddenHolds` lives in the class body)
    private func setIncludesCallsInRecents(_ on: Bool) {
        guard let provider = callProvider, provider.configuration.includesCallsInRecents != on,
              let cfg = provider.configuration.copy() as? CXProviderConfiguration else { return }
        cfg.includesCallsInRecents = on
        provider.configuration = cfg
        CallFileLogger.shared.log("📞 CallKit: includesCallsInRecents=\(on)")
    }

    private func beginRecentsHiddenReport() {
        recentsLock.lock(); recentsHiddenHolds += 1; recentsLock.unlock()
        setIncludesCallsInRecents(false)
    }

    private func endRecentsHiddenReport() {
        VoIPPushManager.pushQueue.asyncAfter(deadline: .now() + 2) { [weak self] in
            guard let self else { return }
            self.recentsLock.lock()
            let wasHeld = self.recentsHiddenHolds > 0
            self.recentsHiddenHolds = max(0, self.recentsHiddenHolds - 1)
            let last = wasHeld && self.recentsHiddenHolds == 0
            self.recentsLock.unlock()
            if last { self.setIncludesCallsInRecents(true) }
        }
    }

    /// Before a REAL call reaches CallKit: Recents back on, whatever is still pending.
    private func restoreRecentsForRealCall() {
        recentsLock.lock(); recentsHiddenHolds = 0; recentsLock.unlock()
        setIncludesCallsInRecents(true)
    }

    /// Report a placeholder CXCall and end it immediately. Only reachable when no
    /// CallKit surface is live (or via the `callkit_placeholder_for_extra_push`
    /// escape hatch), so it can no longer stack a second surface on a ringing call.
    private func reportThrowawayCall(callerPublicKey: String,
                                     displayName: String?,
                                     endReason: CXCallEndedReason,
                                     why: String,
                                     hideFromRecents: Bool = false,
                                     completion: @escaping () -> Void) {
        let placeholderCallUUID = UUID()
        if hideFromRecents { beginRecentsHiddenReport() }
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: MessageManager.resolveContactAlias(callerPublicKey))
        if let displayName { update.localizedCallerName = displayName }
        CallFileLogger.shared.log("📞 SURFACE +callkit(placeholder) uuid=\(placeholderCallUUID.uuidString.prefix(8)) reason=\(why)")
        reportNewIncomingCallFromAnyQueue(placeholderCallUUID, update) { [weak self] error in
            // 🔧 FIX (2026-07-28): the outcome used to be discarded (`{ _ in }`), so a
            // placeholder that iOS never presented still printed a `+/-callkit(placeholder)`
            // surface pair — a log that says a surface existed when none did. Ending a
            // call the system rejected is also meaningless, so skip it when filtered.
            if VoIPPushManager.isCallKitFilterError(error as NSError?) {
                CallFileLogger.shared.log("📞 SURFACE -callkit(placeholder) uuid=\(placeholderCallUUID.uuidString.prefix(8)) reason=\(why) — NEVER PRESENTED (filtered code=\((error as NSError?)?.code ?? -1))")
                self?.noteCallKitWillNotPresent(why: why, code: (error as NSError?)?.code ?? -1)
                if hideFromRecents { self?.endRecentsHiddenReport() }
                completion()
                return
            }
            self?.callProvider?.reportCall(with: placeholderCallUUID, endedAt: Date(), reason: endReason)
            if hideFromRecents { self?.endRecentsHiddenReport() }
            CallFileLogger.shared.log("📞 SURFACE -callkit(placeholder) uuid=\(placeholderCallUUID.uuidString.prefix(8)) reason=\(why)")
            completion()
        }
    }

    func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType, completion: @escaping () -> Void) {
        guard type == .voIP else {
            completion()
            return
        }
        handleIncomingVoIPPush(payload.dictionaryPayload, completion: completion)
    }

    /// __PUSHKIT_OFF_MAIN_2026_09_23__ THE PushKit contract, in one place.
    ///
    /// INVARIANT: every path through this function calls
    /// `CXProvider.reportNewIncomingCall` SYNCHRONOUSLY, before returning — the live
    /// call's UUID for a duplicate/extra push (iOS answers `callUUIDAlreadyExists`, no
    /// second surface), a throwaway UUID ended at once when nothing is live, or the real
    /// call. And it never waits for main: it runs on `pushQueue` (PKPushRegistry's
    /// queue), reads only `callStateLock` state and `CallPushGate` snapshots, and every
    /// main-thread effect (VoiceCallManager setup, NotificationCenter observers) is
    /// `DispatchQueue.main.async`.
    ///
    /// iOS kills the app with FRONTBOARD 0xBAADCA11 when a VoIP push is not reported in
    /// time — measured 2026-09-22 on build 143 with main stuck in a sync JSON encode.
    ///
    /// Internal (not private) so the DEBUG self-test can drive it without a PKPushPayload.
    func handleIncomingVoIPPush(_ data: [AnyHashable: Any], completion rawCompletion: @escaping () -> Void) {
        // PushKit's completion is called exactly once: normally from CallKit's report
        // callback, but CallKit delivers that callback on the provider's delegate queue
        // (main) — so a busy main must not be able to hold the push open either. The
        // report itself has been made by then; the fallback only releases PushKit.
        let once = PushCompletionOnce(rawCompletion)
        let completion: () -> Void = { once.fire() }
        VoIPPushManager.pushQueue.asyncAfter(deadline: .now() + 5) {
            if once.fire() {
                CallFileLogger.shared.log("⏱️ VoIPPush: completion released by the 5 s fallback (CallKit callback still pending — main busy?)")
            }
        }

        let gate = CallPushGate.shared
        CallFileLogger.shared.log("📞 VoIPPush: ═══ RECEIVED VoIP PUSH ═══ appState=\(gate.appState.rawValue) main=\(Thread.isMainThread)")
        OshiLog.call.info("📞 VoIPPush: ════════════════════════════════════")
        OshiLog.call.info("📞 VoIPPush: Received incoming push!")
        OshiLog.call.info("   Payload: \(data)")

        // __LEAK_FIX_2026_09_25__ A payload without `callerName` (new senders ship only
        // the neutral sentinel, or nothing) must still ring: only the key is required.
        guard let callerPublicKey = data["callerPublicKey"] as? String else {
            OshiLog.call.info("❌ VoIPPush: Invalid payload - missing caller info")
            // Must report a call even if we can't parse payload (iOS requirement).
            // Routed through satisfyPushObligation so it can never stack a second
            // surface on a call CallKit is already ringing.
            satisfyPushObligation(callerPublicKey: "unknown", displayName: "Unknown",
                                  endReason: .failed, why: "invalid_payload", completion: completion)
            return
        }
        
        let callerName = data["callerName"] as? String ?? data["caller"] as? String ?? ""
        let callId = data["callId"] as? String ?? UUID().uuidString

        // 🔧 FIX: Resolve caller display name — never show "Unknown"
        // Priority: 1) Saved contact alias, 2) Push callerName (if real), 3) Shortened public key
        let resolvedCallerName: String = {
            // __ALIAS_KEY_FOLD_2026_08_13__ folded `-`/`_` but not the `=` padding,
            // so a saved alias never matched a relay-normalized caller key.
            // __SHARED_NICKNAME_2026_09_22__ alias local, sinon pseudo partage.
            if let alias = MessageManager.lookupContactDisplayName(callerPublicKey) {
                return alias
            }
            // Use push callerName if it's a real name (not "Unknown" or empty)
            if !callerName.isEmpty && callerName != "Unknown" && callerName != "OSHI User" {
                return callerName
            }
            // Fall back to shortened public key (always identifiable)
            return String(MessageManager.normalizeAliasKey(callerPublicKey).prefix(12)) + "..."
        }()

        let appStateStr: String = {
            switch gate.appState {
            case .active: return "active"
            case .inactive: return "inactive"
            case .background: return "background"
            @unknown default: return "unknown"
            }
        }()
        OshiLog.call.info("📞 VoIPPush: Call from \(resolvedCallerName) (raw: \(callerName))")
        OshiLog.call.info("   Caller key: \(callerPublicKey.prefix(16))...")
        OshiLog.call.info("   Call ID: \(callId)")
        OshiLog.call.info("   App state: \(appStateStr)")
        CallFileLogger.shared.log("📞 VoIPPush: ═══ INCOMING ═══ from=\(resolvedCallerName) key=\(callerPublicKey.prefix(12)) callId=\(callId.prefix(8)) appState=\(appStateStr)")
        CallFileLogger.shared.log("📞 VoIPPush: isVideo=\((data["isVideoCall"] as? Bool) ?? false) hasEncryptedSignal=\((data["encryptedSignal"] as? String)?.isEmpty == false)")

        // 🪦 GHOST-RING FIX: reject a STALE call push. A VoIP push delivered late
        // (APNs queueing, or a re-sent/buffered call signal after our dedup
        // window) must never ring for a call that already ended minutes ago —
        // the exact "it rang a few minutes after the caller hung up" bug. A live
        // call rings for at most ~55s, so a call older than that is dead. iOS
        // still requires us to report SOMETHING for every VoIP push, so we
        // report + immediately end it instead of ringing.
        // Robust extraction: JSON push payloads may deliver the timestamp as a
        // Double, Int, NSNumber, or numeric String depending on the server build.
        // The previous `as? Double`-only check FAILED OPEN (skipped staleness and
        // rang unconditionally) for any non-Double form — and this is the single
        // guard against the historical ghost ring, so it must not fail open.
        let callTimestamp: Double? = {
            if let d = data["callTimestamp"] as? Double { return d }
            if let n = data["callTimestamp"] as? NSNumber { return n.doubleValue }
            if let i = data["callTimestamp"] as? Int { return Double(i) }
            if let s = data["callTimestamp"] as? String, let d = Double(s) { return d }
            return nil
        }()
        if let ts = callTimestamp {
            let age = Date().timeIntervalSince1970 - ts
            if age > 60 {
                OshiLog.call.info("🪦 VoIPPush: STALE call (age \(Int(age))s) — reporting+ending, not ringing")
                CallFileLogger.shared.log("🪦 VoIPPush: STALE callId=\(callId.prefix(8)) age=\(Int(age))s — suppressed ring")
                satisfyPushObligation(callerPublicKey: callerPublicKey, displayName: resolvedCallerName,
                                      endReason: .unanswered, why: "stale_push", completion: completion)
                return
            }
        }

        // 🔧 FIX: Deduplicate VoIP pushes by call ID (prune + test + insert, atomically)
        if !markCallIdProcessed(callId) {
            OshiLog.call.info("⚠️ VoIPPush: Duplicate call ID \(callId) - already processed, skipping")
            CallFileLogger.shared.log("⚠️ VoIPPush: DUPLICATE callId=\(callId.prefix(8)) — skipping")

            // 🔧 FIX (2026-07-27b): this is THE path that built the second surface.
            // MEASURED on the iPhone 14 (frz14/call_debug.log 14:27:44): the server
            // delivered the push for callId 7ABB0A55 TWICE inside the same second, and
            // this branch answered the second copy with a fresh placeholder CXCall
            // carrying the caller's real name and handle — so iOS drew a complete
            // second incoming-call surface while the first was still ringing. Two
            // CXEndCallActions for two different UUIDs two seconds later confirm it.
            // satisfyPushObligation re-reports the LIVE UUID instead, which iOS treats
            // as the call it already has: the push obligation is met and no second
            // surface can exist. fd48232 had this behind an opt-in flag that was off.
            satisfyPushObligation(callerPublicKey: callerPublicKey, displayName: resolvedCallerName,
                                  endReason: .answeredElsewhere, why: "duplicate_push", completion: completion)
            return
        }

        // Check if caller is blocked (lock-guarded snapshot of BlockedContactsManager)
        if gate.isBlocked(callerPublicKey) {
            OshiLog.call.info("🚫 VoIPPush: Caller is blocked, ignoring")
            CallFileLogger.shared.log("🚫 VoIPPush: BLOCKED caller=\(callerPublicKey.prefix(12))")
            // Must still report and end call for iOS. No caller name on the update:
            // a blocked caller must not get a branded surface even for the instant
            // before it is ended.
            // __BLOCKED_NO_RECENTS_2026_09_24__ kept out of the system Recents, and never
            // ended as `.unanswered` (= a MISSED call there) — see beginRecentsHiddenReport.
            satisfyPushObligation(callerPublicKey: callerPublicKey, displayName: nil,
                                  endReason: .declinedElsewhere, why: "blocked_caller",
                                  hideFromRecents: true, completion: completion)
            // The blocked-caller notice is NOT sent from here: this handler must not
            // touch main-thread state (VoIPPushOffMainRegressionTests / 0xBAADCA11).
            // The call-signal message that follows the push reaches the same notice
            // via VoiceCallManager / receiveMessage.
            return
        }

        // Stale identity check — caller's public key is one the user already
        // marked as "no longer this person" (manual or auto on QR rotation /
        // group KEY_ROTATION). Drop silently the same way we do for blocked,
        // but flag it as STALE in logs so we can tell the two apart.
        if gate.isStale(callerPublicKey) {
            OshiLog.call.info("🪦 VoIPPush: Caller key is stale (rotated identity), ignoring")
            CallFileLogger.shared.log("🪦 VoIPPush: STALE caller=\(callerPublicKey.prefix(12))")
            // CallKit still requires us to report+end — same pattern as blocked
            satisfyPushObligation(callerPublicKey: callerPublicKey, displayName: nil,
                                  endReason: .unanswered, why: "stale_identity", completion: completion)
            return
        }

        // Check contacts-only whitelist
        let contactsOnly = UserDefaults.standard.bool(forKey: "callsFromContactsOnly")
        if contactsOnly {
            // Snapshot of MessageManager.conversations (kept on main, persisted so a
            // cold launch by the push has it). `nil` = never populated on this install
            // since the update: ring rather than reject a caller we cannot check.
            let known = gate.isContact(callerPublicKey)
            if known == nil {
                CallFileLogger.shared.log("⚠️ VoIPPush: CONTACTS_ONLY — contact snapshot not ready, letting caller=\(callerPublicKey.prefix(12)) ring")
            }
            if known == false {
                OshiLog.call.info("🚫 VoIPPush: Caller not in contacts, rejecting (contacts-only mode)")
                CallFileLogger.shared.log("🚫 VoIPPush: CONTACTS_ONLY rejected caller=\(callerPublicKey.prefix(12))")
                satisfyPushObligation(callerPublicKey: callerPublicKey, displayName: nil,
                                      endReason: .unanswered, why: "contacts_only", completion: completion)
                return
            }
        }

        // Store pending call data (use resolved name)
        pendingCallData = [
            "callerPublicKey": callerPublicKey,
            "callerName": resolvedCallerName,
            "callId": callId,
            "encryptedSignal": data["encryptedSignal"] as? String ?? ""
        ]
        
        // 🔧 FIX: Notify VoiceCallManager that VoIP push is handling this call
        // This suppresses VPS polling and in-app UI to prevent duplicates
        // __PUSHKIT_OFF_MAIN_2026_09_23__ observers (VoiceCallManager.handleVoIPPushActive)
        // are main-thread code: post there, asynchronously. FIFO keeps it ahead of the
        // surface claim / in-app setup that reportIncomingCall queues next.
        DispatchQueue.main.async {
            NotificationCenter.default.post(
                name: NSNotification.Name("VoIPPushReceived"),
                object: nil,
                userInfo: ["callerPublicKey": callerPublicKey, "callId": callId]
            )
        }

        // 🔧 FIX: Check if VoiceCallManager is already ringing from the same caller (via mesh)
        // This prevents showing BOTH the in-app overlay AND CallKit UI at the same time.
        // Read through the gate's lock-guarded mirror of VoiceCallManager, never the
        // instance itself (main-thread state; and touching `.shared` here on a cold
        // launch would build the whole manager on the PushKit queue).
        let voiceManager = gate

        // 🔧 CRITICAL: Block ALL duplicate call notifications during active/ringing calls
        // Previously only checked same-peer, but key normalization could fail.
        // Any call notification during an active call disrupts audio engine and crashes the call.
        // __PHANTOM_RING_2026_08_27__ Une poussée pour un appel DÉJÀ TERMINÉ ne
        // doit pas faire sonner. C'est le seul chemin qui pouvait: les gardes
        // ci-dessous portent sur `callState`, et à l'état `.idle` — celui d'après
        // un raccrochage — aucune ne s'applique.
        //
        // L'appelant peut continuer à réémettre pendant plusieurs secondes
        // (poussée VoIP + fan-out VPS), et le journal du 27 août montre deux
        // poussées pour le même `callId` arrivant après coup. Elles n'ont été
        // bloquées que parce que l'appel sonnait ENCORE.
        //
        // ⚠️ Une RECONNEXION ne termine pas l'appel: son `callId` n'est jamais
        // marqué, donc rien de ce qui la concerne n'est filtré. Le test porte sur
        // l'identité d'un appel mort, pas sur l'âge d'une poussée.
        if voiceManager.isCallIdRecentlyEnded(callId) {
            OshiLog.call.info("📞 VoIPPush: ⚠️ callId \(callId) already ended — not ringing")
            CallFileLogger.shared.log("⚠️ VoIPPush: BLOCKED — callId \(callId) recently ended (phantom ring)")
            satisfyPushObligation(callerPublicKey: callerPublicKey, displayName: nil,
                                  endReason: .answeredElsewhere, why: "call_already_ended", completion: completion)
            return
        }

        if voiceManager.callState == .inCall || voiceManager.callState == .connecting {
            OshiLog.call.info("📞 VoIPPush: ⚠️ Already in active call (state: \(voiceManager.callState)) - blocking ALL new calls")
            OshiLog.call.info("📞 VoIPPush: ════════════════════════════════════")
            CallFileLogger.shared.log("⚠️ VoIPPush: BLOCKED — already in active call (state: \(voiceManager.callState))")
            // Must still report to iOS (PushKit requirement) then immediately end it —
            // but never as a NEW named call, or the live call gets a second surface.
            satisfyPushObligation(callerPublicKey: callerPublicKey, displayName: resolvedCallerName,
                                  endReason: .answeredElsewhere, why: "already_in_call", completion: completion)
            return
        }

        if voiceManager.callState == .ringing {
            OshiLog.call.info("📞 VoIPPush: ⚠️ Already ringing (state: \(voiceManager.callState))")
            OshiLog.call.info("📞 VoIPPush: ════════════════════════════════════")
            // We are ALREADY ringing this call — two sub-cases:
            //
            //  • CallKit already owns the surface (`callKitPresentingCallId != nil`):
            //    re-report the live UUID; iOS treats it as the call it already knows
            //    and draws NOTHING new. This is the safe coalesce path.
            //
            //  • The IN-APP overlay owns the surface (a mesh/WebSocket signal beat the
            //    push, so no CallKit call exists yet). 🔧 FIX (2026-09-15, "full
            //    screen + banner 2 s later"): the old code reported an UNNAMED
            //    throwaway placeholder and ended it — but reportNewIncomingCall
            //    PRESENTS a banner regardless of the name (unless iOS happens to
            //    filter it for Focus/DND), so this is exactly the second surface the
            //    user saw flash in ~2 s after the in-app overlay. Instead, PROMOTE
            //    the call to a REAL CallKit incoming call: CallKit becomes the SINGLE
            //    persistent surface, the in-app overlay collapses the instant
            //    `incomingCallSurfaceOwner` flips to `.callKit`, the PushKit
            //    obligation is met by a genuine report, and any later duplicate push
            //    coalesces onto this now-live UUID (no flash). Push-less calls (pure
            //    mesh / China bypass) never reach this handler, so their in-app
            //    overlay is untouched.
            if presentingCallIdSync == nil {
                CallFileLogger.shared.log("📞 VoIPPush: already ringing via IN-APP — PROMOTING to CallKit (single surface) callId=\(callId.prefix(8))")
                let isVideoCall = (data["isVideoCall"] as? Bool) ?? (data["isVideo"] as? Bool) ?? false
                reportIncomingCall(
                    callerPublicKey: callerPublicKey,
                    callerName: resolvedCallerName,
                    callId: callId,
                    isVideoCall: isVideoCall
                ) { _ in completion() }
                return
            }
            CallFileLogger.shared.log("⚠️ VoIPPush: already ringing via CallKit — coalescing on live UUID")
            satisfyPushObligation(callerPublicKey: callerPublicKey, displayName: nil,
                                  endReason: .answeredElsewhere, why: "already_ringing", completion: completion)
            return
        }

        // Extract video call flag from push payload
        let isVideoCall = (data["isVideoCall"] as? Bool) ?? (data["isVideo"] as? Bool) ?? false

        // Report incoming call to CallKit (with resolved name)
        CallFileLogger.shared.log("📞 VoIPPush: Reporting to CallKit: name=\(resolvedCallerName) isVideo=\(isVideoCall)")
        let pushReceivedTime = Date()
        reportIncomingCall(
            callerPublicKey: callerPublicKey,
            callerName: resolvedCallerName,
            callId: callId,
            isVideoCall: isVideoCall
        ) { error in
            let elapsed = Date().timeIntervalSince(pushReceivedTime)
            if let error = error {
                CallFileLogger.shared.log("❌ VoIPPush: CallKit report FAILED after \(String(format: "%.1f", elapsed))s: \(error.localizedDescription)")
            } else {
                CallFileLogger.shared.log("✅ VoIPPush: CallKit reported OK after \(String(format: "%.1f", elapsed))s")
            }
            OshiLog.call.info("📞 VoIPPush: ════════════════════════════════════")
            CallFileLogger.shared.log("📞 VoIPPush: completion() called — push handling complete")
            completion()
        }
    }
    
    func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
        guard type == .voIP else { return }

        OshiLog.call.info("⚠️ VoIPPush: Token invalidated")
        CallFileLogger.shared.log("⚠️ VoIPPush: TOKEN INVALIDATED — re-registering for new token")
        // Published state → main (this delegate now runs on `pushQueue`).
        DispatchQueue.main.async {
            self.voipToken = nil
            self.isRegistered = false
            // Re-register for new token
            registry.desiredPushTypes = [.voIP]
        }
    }
}

// MARK: - CXProviderDelegate

extension VoIPPushManager: CXProviderDelegate {
    
    func providerDidReset(_ provider: CXProvider) {
        OshiLog.call.info("📞 CallKit: Provider reset")
        CallFileLogger.shared.log("📞 CallKit: PROVIDER RESET — cleaning up all call state")

        // Clean up any call state
        setCallKitPresenting(nil, reason: "provider_reset")
        currentCallUUID = nil
        currentCallerPublicKey = nil
        currentCallerName = nil

        // Notify VoiceCallManager to end any active call
        VoiceCallManager.shared.endCall(reason: .networkError)
    }
    
    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        OshiLog.call.info("📞 CallKit: Start call action")

        // Configure audio session for call
        configureAudioSession()

        // 🔧 FIX (2026-08-07, "redial from call history just opens OSHI"):
        // this handler configured audio and fulfilled — it never PLACED a call.
        // Tapping an OSHI entry in the system call history therefore launched
        // the app and stopped, which is exactly what was reported.
        //
        // iOS delivers the redial as a CXStartCallAction carrying the handle we
        // wrote when the call was made. That handle is the alias (see above),
        // so it has to be mapped BACK to a public key before dialling — the
        // reverse of the lookup that made the history readable.
        let dialled = action.handle.value
        // __BLOCKED_BY_PEER_2026_09_24__ a redial from Recents to someone who told us they
        // block us (or whom we block) fails right here: CallKit shows "Call Failed", and no
        // offer / VoIP push leaves this device.
        if let peerKey = Self.publicKeyForHandle(dialled),
           BlockedByPeerStore.shared.isBlockedBy(peerKey) || BlockedContactsManager.shared.isBlocked(peerKey) {
            OshiLog.call.info("📞 CallKit redial: peer unavailable/blocked — failing the start action")
            action.fail()
            return
        }
        if let peerKey = Self.publicKeyForHandle(dialled) {
            Task { @MainActor in
                try? await VoiceCallManager.shared.startCall(
                    to: peerKey,
                    peerName: dialled,
                    isVideoCall: action.isVideo
                )
            }
        } else {
            OshiLog.call.info("📞 CallKit redial: no contact matches handle \(dialled) — opening the app only")
        }

        action.fulfill()
    }

    /// Reverse of `MessageManager.resolveContactAlias` — find whose key a
    /// CallKit handle belongs to.
    ///
    /// Tries the alias table first, then accepts a raw public key, because
    /// history entries written by older builds carry the key itself. Returns nil
    /// rather than guessing: dialling the wrong person is worse than not
    /// dialling, and two contacts CAN share an alias.
    static func publicKeyForHandle(_ handle: String) -> String? {
        guard !handle.isEmpty else { return nil }
        if let data = UserDefaults.standard.data(forKey: "contactAliases"),
           let aliases = try? JSONDecoder().decode([String: String].self, from: data) {
            let hits = aliases.filter { $0.value == handle }
            if hits.count == 1 { return hits.first?.key }
            if hits.count > 1 {
                OshiLog.call.info("📞 CallKit redial: \(hits.count) contacts share the name \(handle) — refusing to guess")
                return nil
            }
        }
        // An older entry, or a contact with no alias: the handle IS the key.
        return handle.count > 20 ? handle : nil
    }
    
    /// 🔧 FIX (2026-07-27b) — THE mechanism behind the surviving duplicate surface.
    ///
    /// Every "we must report something to satisfy PushKit" path used to report a
    /// throwaway CXCall with a FRESH UUID (duplicate push, stale push, blocked caller,
    /// contacts-only, already-in-a-call, already-ringing). That placeholder is a real
    /// CallKit call as far as iOS is concerned, so iOS drew a real second incoming-call
    /// surface for it — and neither `CXAnswerCallAction` nor `CXEndCallAction` checked
    /// `action.callUUID`. So an action aimed at the PLACEHOLDER ran against the LIVE
    /// call: it cleared `callKitPresentingCallId`, `currentCallUUID` and the caller
    /// info while CallKit was still ringing the real call. Clearing the flag is exactly
    /// what un-gated ContentView's branch → CallKit banner AND our full-screen overlay
    /// on screen at the same time. Measured on the iPhone 14 in frz14/call_debug.log
    /// 14:27:44-46: two "═══ RECEIVED VoIP PUSH ═══" for callId 7ABB0A55, then two
    /// CXEndCallActions for two DIFFERENT UUIDs (3E551249 and 21A59C18) and a
    /// "CXAnswerCallAction — no caller info!" in between.
    ///
    /// `CXSetMutedCallAction` already had this guard; answer/end did not.
    ///
    /// Returns true (and fulfils the action, so iOS dismisses the stray surface) when
    /// the action does NOT belong to the call we are running. When `currentCallUUID`
    /// is nil we have no live call to protect and stay permissive — a call must never
    /// become un-hangup-able because of this guard.
    private func isForeignCallAction(_ callUUID: UUID, _ what: String) -> Bool {
        guard let live = currentCallUUID, callUUID != live else { return false }
        OshiLog.call.info("🚫 CallKit: ignoring \(what) for a foreign call UUID")
        CallFileLogger.shared.log(
            "🚫 CallKit: \(what) IGNORED — uuid=\(callUUID.uuidString.prefix(8)) is not the live call \(live.uuidString.prefix(8)) (stray placeholder surface)")
        return true
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        OshiLog.call.info("📞 CallKit: Answer call action")
        CallFileLogger.shared.log("📞 CallKit: USER TAPPED ACCEPT — CXAnswerCallAction fired")

        // Never let a stray placeholder CXCall answer (or tear down) the live call.
        if isForeignCallAction(action.callUUID, "CXAnswerCallAction") {
            action.fulfill()
            return
        }

        // 🔧 FIX (2026-07-27): this used to swallow the accept whenever the app was
        // foreground ("the in-app overlay owns accept"). The in-app overlay no longer
        // draws while CallKit is presenting, so swallowing the tap made a foreground
        // call literally unanswerable. It is also the answer path we WANT: it sets
        // VoiceCallManager.wasAnsweredViaCallKit, which is what stops the app from
        // calling reportCall(endedAt:.answeredElsewhere) on itself and lets CallKit
        // keep — and activate — the audio session (didActivate → engine on a live
        // session, instead of the observed setActive 561017449 loop).
        //
        // The call is answered, so CallKit's *incoming* surface is done; the CXCall
        // itself lives on and keeps owning the audio session.
        setCallKitPresenting(nil, reason: "answered")

        // Configure audio session
        configureAudioSession()

        // Get caller info
        guard let callerKey = currentCallerPublicKey,
              let callerName = currentCallerName else {
            OshiLog.call.info("❌ CallKit: No caller info available")
            CallFileLogger.shared.log("❌ CallKit: CXAnswerCallAction — no caller info!")
            action.fail()
            return
        }

        OshiLog.call.info("📞 CallKit: Answering call from \(callerName)")
        CallFileLogger.shared.log("📞 CallKit: answering from=\(callerName) key=\(callerKey.prefix(12)) pendingData=\(pendingCallData != nil)")
        
        // Notify app to accept the call
        NotificationCenter.default.post(
            name: NSNotification.Name("CallKitAnswerCall"),
            object: nil,
            userInfo: [
                "callerPublicKey": callerKey,
                "callerName": callerName,
                "callUUID": action.callUUID.uuidString
            ]
        )
        
        action.fulfill()
    }
    
    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        OshiLog.call.info("📞 CallKit: End call action")
        CallFileLogger.shared.log("📞 CallKit: CXEndCallAction — user ended call from CallKit UI")

        // A decline on a stray placeholder surface must NOT end the live call, and
        // must NOT release the incoming surface (that is what let the in-app overlay
        // appear underneath CallKit's still-visible banner). Fulfil so iOS drops the
        // stray surface, then stop.
        if isForeignCallAction(action.callUUID, "CXEndCallAction") {
            action.fulfill()
            return
        }

        // Notify app to end the call
        NotificationCenter.default.post(
            name: NSNotification.Name("CallKitEndCall"),
            object: nil,
            userInfo: ["callUUID": action.callUUID.uuidString]
        )

        // CallKit dropped the call — release the incoming surface too, otherwise a
        // decline from the CallKit screen would leave every view believing CallKit
        // is still ringing and the NEXT call would have no UI at all.
        setCallKitPresenting(nil, reason: "cx_end")
        currentCallUUID = nil
        currentCallerPublicKey = nil
        currentCallerName = nil

        action.fulfill()
    }
    
    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        OshiLog.call.info("📞 CallKit: Mute action - muted: \(action.isMuted)")

        // 🔧 FIX: only honour a mute action that targets the call we are actually
        // running. Without this UUID check, a stale/other CallKit call (e.g. one
        // we just reported ended) could toggle the live call's mute — and a call
        // that starts muted is indistinguishable from "no audio".
        guard action.callUUID == currentCallUUID else {
            CallFileLogger.shared.log("📞 CallKit: ignoring CXSetMutedCallAction for foreign UUID \(action.callUUID.uuidString.prefix(8))")
            action.fulfill()
            return
        }

        // Toggle mute in VoiceCallManager
        if action.isMuted != VoiceCallManager.shared.isMuted {
            VoiceCallManager.shared.toggleMute()
        }

        action.fulfill()
    }
    
    func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        // We don't support hold, but must fulfill
        action.fulfill()
    }
    
    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        OshiLog.call.info("📞 CallKit: Audio session activated by system")

        // 🔧 FIX (2026-07-27, earpiece unreachable): the forced
        // `overrideOutputAudioPort(.speaker)` that used to sit here is GONE. It was a
        // second owner of the route: it yanked a receiver-mode call back onto the
        // loudspeaker on every re-activation, and it also bypassed the AirPods
        // detection in applyInitialCallAudioRoute (which the CallKit-answered path
        // never reaches on its own). The notification below wakes
        // VoiceCallManager.handleCallKitAudioActivated, which now owns the route:
        // handsfree default on the FIRST activation, the user's live choice after.

        // Notify that audio is ready
        NotificationCenter.default.post(
            name: NSNotification.Name("CallKitAudioSessionActivated"),
            object: nil
        )
    }
    
    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        OshiLog.call.info("📞 CallKit: Audio session deactivated")
        CallFileLogger.shared.log("📞 CallKit: AUDIO SESSION DEACTIVATED")
        // 🔧 FIX (2026-07-26): this handler was implemented but EMPTY, so the
        // AVAudioEngine graph outlived the session it was built on. An engine
        // whose IO unit is bound to a dead session keeps reporting
        // `isRunning == true` while capturing silence and rendering to nothing.
        // VoiceCallManager observes this and stops the engine so the next
        // didActivate rebuilds it cleanly.
        NotificationCenter.default.post(
            name: NSNotification.Name("CallKitAudioSessionDeactivated"),
            object: nil
        )
    }
    
    func provider(_ provider: CXProvider, timedOutPerforming action: CXAction) {
        OshiLog.call.info("⚠️ CallKit: Action timed out: \(action)")
        action.fail()
    }
    
    // MARK: - Audio Session
    
    private func configureAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()

            // 🔧 FIX: Configure category/mode but do NOT call setActive(true) here.
            // CallKit manages audio session activation — it will call didActivate when ready.
            // Calling setActive(true) before CallKit's didActivate can cause conflicts where
            // the audio engine starts on a pre-activated session, then CallKit re-activates
            // and the engine loses its connection to hardware → no audio.
            // 🔧 FIX: Use .allowBluetoothHFP (not A2DP) — HFP supports mic+speaker for calls
            try session.setCategory(.playAndRecord, mode: .videoChat, options: [.defaultToSpeaker, .allowBluetoothHFP])
            try session.setPreferredSampleRate(48000)
            try session.setPreferredIOBufferDuration(0.005)

            let hasEarpiece = UIDevice.current.userInterfaceIdiom == .phone
            OshiLog.call.info("✅ CallKit: Audio session configured (.videoChat mode, activation deferred to didActivate)")
            OshiLog.call.info("   Device: \(hasEarpiece ? "iPhone" : "iPad/Mac")")
        } catch {
            OshiLog.call.info("❌ CallKit: Failed to configure audio session: \(error)")
        }
    }
}

// MARK: - APNs Push Notification Helper

extension VoIPPushManager {
    
    /// Register APNs token (called from AppDelegate)
    func registerAPNsToken(_ token: Data) {
        let tokenString = token.map { String(format: "%02.2hhx", $0) }.joined()

        DispatchQueue.main.async {
            self.apnsToken = tokenString
            OshiLog.call.info("📱 APNs: Token received")
            OshiLog.call.info("   Token: \(tokenString.prefix(32))...")
            CallFileLogger.shared.log("📱 APNs: Token received (\(tokenString.prefix(16))...) voipToken=\(self.voipToken != nil)")

            // 🔧 FIX: Cancel any pending delayed registration and register now with both tokens
            self.registrationWorkItem?.cancel()
            self.registrationWorkItem = nil

            // 🔧 FIX: Register as soon as we have the APNs token. The VoIP token
            // (calls) is optional and must NOT gate message-push registration —
            // see registerTokensWithVPS(). Previously, if the VoIP token never
            // arrived, this branch fell through to "waiting" forever and the
            // device never registered for MESSAGE pushes. If the VoIP token
            // arrives later, pushRegistry(didUpdate:) re-runs registration with it.
            OshiLog.call.info("📱 APNs: registering with VPS (voipToken=\(self.voipToken != nil))")
            CallFileLogger.shared.log("📱 APNs: registering with VPS voipToken=\(self.voipToken != nil)")
            self.registerTokensWithVPS()
        }
    }
}

// MARK: - Server-Side Push Notification Request

/// Structure for requesting push notifications from server
struct PushNotificationRequest: Codable {
    let recipientPublicKey: String
    let type: String  // "call" or "message"
    let title: String
    let body: String
    let data: [String: String]
}

extension VoIPPushManager {
    
    // 🔧 FIX (2026-07-27): `sendCallPushAPNs` deleted — it had ZERO callers.
    // It POSTed to /send-call-apns, the server route that produces the alert
    // notification (title "Incoming Call", body "OSHI Encrypted Call",
    // category INCOMING_CALL, sound ringtone.caf). Keeping it around invited the
    // reading that this alert is a possible third incoming-call surface; it is not
    // reachable from the app's own flow. China-mode devices still get that alert,
    // but push_service.js raises it itself from /send-voip-push for devices
    // registered `isChina` — no client call is involved, so this deletion cannot
    // change behaviour on any device.

    /// Send VoIP push notification for incoming call
    /// This wakes up the recipient's device and shows native call UI
    static func sendVoIPPush(
        to recipientPublicKey: String,
        callerPublicKey: String,
        callerName: String,
        callId: String,
        isVideoCall: Bool = false
    ) async throws {
        // __LEAK_FIX_2026_09_25__ The caller's real display name used to ride in the push
        // (readable by the push server, APNs and FCM). The callee resolves the name locally
        // from `callerPublicKey`. The field itself stays, set to the neutral sentinel every
        // receiver already treats as "no name" (iOS since 2026-03; Android shows it as-is
        // and requires nothing), because iOS ≤ 1.0.43 REJECTS a VoIP payload without it.
        _ = callerName
        var payload: [String: Any] = [
            "recipientPublicKey": recipientPublicKey,
            "callerPublicKey": callerPublicKey,
            "callerName": VoIPPushManager.neutralCallerName,
            "callId": callId,
            // Call start time (epoch seconds). The callee refuses to ring for a
            // push whose call is already stale (delivered/re-sent minutes late),
            // which is what caused the "ghost ring after the caller hung up".
            "callTimestamp": Date().timeIntervalSince1970
        ]
        if isVideoCall {
            payload["isVideoCall"] = true
        }

        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload) else {
            throw NSError(domain: "VoIPPush", code: -2, userInfo: [NSLocalizedDescriptionKey: "JSON encoding failed"])
        }

        OshiLog.call.info("📲 VoIPPush: Sending to \(recipientPublicKey.prefix(12))...")
        OshiLog.call.info("   Call ID: \(callId)")

        // Try nginx proxy first, then fallback to direct port
        let urls = [
            "\(shared.pushServiceURL)/send-voip-push",      // /push/send-voip-push via nginx
            "\(shared.pushServiceDirectURL)/send-voip-push" // :8084/send-voip-push direct
        ]

        var lastError: Error?
        for urlString in urls {
            guard let url = URL(string: urlString) else { continue }

            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = jsonData
            request.timeoutInterval = 5
            shared.signRequest(&request)

            do {
                let (responseData, response) = try await URLSession.shared.data(for: request)

                guard let httpResponse = response as? HTTPURLResponse else {
                    continue
                }

                if httpResponse.statusCode == 200 || httpResponse.statusCode == 201 {
                    OshiLog.call.info("✅ VoIPPush: Successfully sent via \(urlString.contains(":8084") ? "direct port" : "nginx")")
                    return
                } else if httpResponse.statusCode == 404 {
                    OshiLog.call.info("⚠️ VoIPPush: Recipient not registered for push notifications")
                    // Don't throw - call can still work via polling
                    return
                } else {
                    let errorMessage = String(data: responseData, encoding: .utf8) ?? "Unknown error"
                    OshiLog.call.info("⚠️ VoIPPush: Server error \(httpResponse.statusCode) via \(urlString): \(errorMessage)")
                    lastError = NSError(domain: "VoIPPush", code: httpResponse.statusCode, userInfo: [NSLocalizedDescriptionKey: errorMessage])
                }
            } catch {
                OshiLog.call.info("⚠️ VoIPPush: Failed via \(urlString): \(error.localizedDescription)")
                lastError = error
            }
        }

        if let error = lastError {
            throw error
        }
    }
    
    /// Request server to send push notification to a user
    /// Use this when sending a message or initiating a call
    ///
    /// __SILENT_FLAG_WAS_DECORATION_2026_09_02__
    /// - Parameter silent: `true` for a WAKE — the peer's app should run, the
    ///   peer's human should see nothing. It travels as `data.silent = "true"`,
    ///   which is honoured in exactly two places, both carrying this marker:
    ///     * `ServerVPS/push_service.js` `/send-push` — sends content-available
    ///       ONLY, as `apns-push-type: background` (and data-only on FCM). This
    ///       is the mechanism; everything else is a fallback.
    ///     * `OSHINotificationService/NotificationService.swift` — drops the
    ///       banner if an un-redeployed server still sends an alert.
    ///
    ///   It is a PARAMETER and not a hand-written `additionalData` entry because
    ///   that is how it was written before, and nobody downstream read it: the
    ///   only caller passed `title: ""`, `body: ""` and `"silent": "true"` and
    ///   got a banner titled with the sender's own name, with sound and badge,
    ///   for a pure group-member sync request. Empty strings do not silence
    ///   anything — the server reads them as `title || 'OSHI'`.
    static func requestPushNotification(
        to recipientPublicKey: String,
        type: String,
        title: String,
        body: String,
        additionalData: [String: String] = [:],
        silent: Bool = false
    ) async throws {
        var data = additionalData
        data["type"] = type
        if silent { data["silent"] = "true" }

        let payload: [String: Any] = [
            "recipientPublicKey": recipientPublicKey,
            "type": type,
            "title": title,
            "body": body,
            "data": data
        ]

        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload) else {
            throw NSError(domain: "VoIPPush", code: -2, userInfo: [NSLocalizedDescriptionKey: "JSON encoding failed"])
        }

        // Try nginx proxy first, then fallback to direct port
        let urls = [
            "\(shared.pushServiceURL)/send-push",      // /push/send-push via nginx
            "\(shared.pushServiceDirectURL)/send-push" // :8084/send-push direct
        ]

        var lastError: Error?
        for urlString in urls {
            guard let url = URL(string: urlString) else { continue }

            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = jsonData
            request.timeoutInterval = 5
            shared.signRequest(&request)

            do {
                let (_, response) = try await URLSession.shared.data(for: request)

                if let httpResponse = response as? HTTPURLResponse,
                   httpResponse.statusCode == 200 || httpResponse.statusCode == 201 {
                    OshiLog.call.info("✅ Push notification sent via \(urlString.contains(":8084") ? "direct port" : "nginx")")
                    return
                }
            } catch {
                OshiLog.call.info("⚠️ Push failed via \(urlString): \(error.localizedDescription)")
                lastError = error
            }
        }

        throw lastError ?? NSError(domain: "VoIPPush", code: -3, userInfo: [NSLocalizedDescriptionKey: "All push endpoints failed"])
    }
}

// MARK: - CallKit delegate proxy (__PUSHKIT_OFF_MAIN_2026_09_23__)

/// Receives CXProviderDelegate callbacks on `VoIPPushManager.callKitDelegateQueue` and
/// re-posts each one to main, asynchronously and in order — the delivery the manager's
/// handlers were written for. Implements EXACTLY the methods `VoIPPushManager` does:
/// CallKit changes its default handling for actions a delegate does not implement.
final class CallKitMainHopDelegate: NSObject, CXProviderDelegate {
    private weak var target: VoIPPushManager?
    init(target: VoIPPushManager) { self.target = target }

    private func onMain(_ body: @escaping (VoIPPushManager) -> Void) {
        DispatchQueue.main.async { [weak target] in
            guard let target else { return }
            body(target)
        }
    }

    func providerDidReset(_ provider: CXProvider) { onMain { $0.providerDidReset(provider) } }
    func provider(_ provider: CXProvider, perform action: CXStartCallAction) { onMain { $0.provider(provider, perform: action) } }
    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) { onMain { $0.provider(provider, perform: action) } }
    func provider(_ provider: CXProvider, perform action: CXEndCallAction) { onMain { $0.provider(provider, perform: action) } }
    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) { onMain { $0.provider(provider, perform: action) } }
    func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) { onMain { $0.provider(provider, perform: action) } }
    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) { onMain { $0.provider(provider, didActivate: audioSession) } }
    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) { onMain { $0.provider(provider, didDeactivate: audioSession) } }
    func provider(_ provider: CXProvider, timedOutPerforming action: CXAction) { onMain { $0.provider(provider, timedOutPerforming: action) } }
}

// MARK: - DEBUG self-test: VoIP push while MAIN is blocked (__PUSHKIT_OFF_MAIN_2026_09_23__)

#if DEBUG
/// Launch with `-OSHIVoIPMainBlockedSelfTest 1` (simulator or device). Reproduces the
/// 2026-09-22 kill scenario without APNs: main is frozen for 8 s (standing in for the
/// multi-second JSON encode), and while it is frozen two pushes for the SAME callId
/// (call_server fallback + the caller's own /send-voip-push) and one for another callId
/// are fed to `handleIncomingVoIPPush` on the PushKit queue. PASS requires each handler
/// to RETURN (i.e. to have made its synchronous `reportNewIncomingCall`) while main is
/// still frozen, and a `CXCallObserver` on a private queue to see the system register
/// the call before main thaws. Results: `Documents/voip_mainblocked_selftest.log`.
final class VoIPMainBlockedSelfTest: NSObject, CXCallObserverDelegate {
    static let shared = VoIPMainBlockedSelfTest()
    private let observer = CXCallObserver()
    private let observerQueue = DispatchQueue(label: "com.oshi.voip.selftest.observer")
    private let lock = NSLock()
    private var lines: [String] = []
    private var t0 = Date()
    private var mainFrozenUntil = Date.distantPast
    private var observedWhileFrozen = false
    private var returnsWhileFrozen = 0

    private func log(_ s: String) {
        let line = String(format: "[+%6.3fs] ", Date().timeIntervalSince(t0)) + s
        lock.lock(); lines.append(line); lock.unlock()
        CallFileLogger.shared.log("🧪 VOIP-SELFTEST " + line)
        OshiLog.call.info("🧪 VOIP-SELFTEST \(line)")
    }

    func callObserver(_ callObserver: CXCallObserver, callChanged call: CXCall) {
        let frozen = Date() < mainFrozenUntil
        if frozen && !call.hasEnded { lock.lock(); observedWhileFrozen = true; lock.unlock() }
        log("CXCallObserver: uuid=\(call.uuid.uuidString.prefix(8)) outgoing=\(call.isOutgoing) connected=\(call.hasConnected) ended=\(call.hasEnded) mainFrozen=\(frozen) onMain=\(Thread.isMainThread)")
    }

    func run() {
        t0 = Date()
        observer.setDelegate(self, queue: observerQueue)
        let callId = "selftest-" + UUID().uuidString.prefix(8)
        let payload: (String) -> [AnyHashable: Any] = { id in
            [
                "callerPublicKey": "SELFTEST0000000000000000000000000000000000A=",
                "callerName": "VoIP self-test",
                "callId": id,
                "callTimestamp": Date().timeIntervalSince1970,
                "isVideoCall": false,
            ]
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [self] in
            let freeze: TimeInterval = 8
            mainFrozenUntil = Date().addingTimeInterval(freeze)
            log("freezing MAIN for \(Int(freeze)) s (stands in for uploadMessagesForSync's encode)")
            let pushes: [(String, String)] = [("push#1 callId", callId), ("push#2 SAME callId (duplicate)", callId), ("push#3 other callId", callId + "-b")]
            for (i, p) in pushes.enumerated() {
                VoIPPushManager.pushQueue.asyncAfter(deadline: .now() + 0.5 + Double(i) * 0.4) { [self] in
                    let start = Date()
                    log("\(p.0) → handler start (onMain=\(Thread.isMainThread))")
                    VoIPPushManager.shared.handleIncomingVoIPPush(payload(p.1)) { [self] in
                        log("\(p.0) → PushKit completion fired (mainFrozen=\(Date() < mainFrozenUntil))")
                    }
                    let frozen = Date() < mainFrozenUntil
                    if frozen { lock.lock(); returnsWhileFrozen += 1; lock.unlock() }
                    log(String(format: "%@ → handler RETURNED after %.1f ms, mainFrozen=%@", p.0, Date().timeIntervalSince(start) * 1000, frozen ? "YES" : "no"))
                }
            }
            Thread.sleep(forTimeInterval: freeze)   // the frozen main thread
            log("MAIN thawed")
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [self] in
                VoIPPushManager.shared.endCall(reason: .remoteEnded)
                lock.lock(); let r = returnsWhileFrozen, o = observedWhileFrozen; lock.unlock()
                // The PushKit contract is the first number. The second needs a working
                // callservicesd: on the simulator CallKit answers with a provider reset and
                // never registers the call, so it is informational there.
                log("RESULT \(r == pushes.count ? "PASS" : "FAIL"): handlers returned (report issued) while main frozen = \(r)/\(pushes.count); system registered the call while main frozen = \(o)\(o ? "" : " (expected NO on the simulator: CallKit inert)")")
                lock.lock(); let text = lines.joined(separator: "\n") + "\n"; lock.unlock()
                if let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
                    try? text.write(to: docs.appendingPathComponent("voip_mainblocked_selftest.log"), atomically: true, encoding: .utf8)
                }
            }
        }
    }
}
#endif
