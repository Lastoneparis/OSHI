//
//  StrictSecurityMode.swift
//  OSHI
//
//  __STRICT_SECURITY_MODE_2026_11_22__ « Mode sécurité renforcée ».
//
//  Owner's request: "a mode in option settings for all, like security: when
//  activated, no copy/paste, no possibility for an AI agent to take control, and
//  any other security." Shared spec with Android and desktop (STRICT_MODE_SPEC).
//
//  ONE switch. While it is on it FORCES settings that already exist (each keeps
//  its own enforcement site — nothing is duplicated) and turns on a few guards
//  that only exist for this mode. Turning it off puts every forced setting back
//  to exactly what it was before, including "never set" (the key is removed, so
//  the setting's own default applies again).
//
//  Local to this device: plain UserDefaults, never synced, never sent anywhere.
//

import Foundation
import UIKit
import SwiftUI
import LocalAuthentication
import CoreSpotlight
import Intents
import ObjectiveC

// MARK: - State + forced settings

enum StrictSecurityMode {
    static let enabledKey = "strictSecurityMode.enabled"
    /// Sub-toggle, default ON: paste into OSHI's text fields is refused too.
    static let blockPasteKey = "strictSecurityMode.blockPaste"
    /// Sub-toggle, default ON: read receipts and typing indicators off.
    static let hideReceiptsKey = "strictSecurityMode.hideReceipts"
    /// What each forced key held before the mode forced it.
    static let savedValuesKey = "strictSecurityMode.savedValues"
    /// Stand-in for "this key had never been written".
    static let absentMarker = "__strict_absent__"

    static let didChangeNotification = Notification.Name("OSHIStrictSecurityModeDidChange")
    /// Posted right after a successful device authentication made to turn the
    /// mode ON, so the App can count it as an unlock: forcing the app lock on
    /// must not throw the person who just authenticated onto the lock screen.
    static let didAuthenticateNotification = Notification.Name("OSHIStrictSecurityModeDidAuthenticate")

    // Keys of the settings the mode forces. Literal where the owning type keeps
    // its key private to a view; the owning constant otherwise, so they cannot drift.
    static let readReceiptsKey = "readReceiptsEnabled"
    static let typingIndicatorsKey = "typingIndicatorsEnabled"
    static let biometricKey = "biometricEnabled"
    static let autoLockKey = "autoLockEnabled"

    static var isOn: Bool { isOn(.standard) }
    static func isOn(_ d: UserDefaults) -> Bool { d.bool(forKey: enabledKey) }

    static func blocksPaste(_ d: UserDefaults = .standard) -> Bool {
        isOn(d) && (d.object(forKey: blockPasteKey) as? Bool ?? true)
    }

    static func hidesReceipts(_ d: UserDefaults = .standard) -> Bool {
        d.object(forKey: hideReceiptsKey) as? Bool ?? true
    }

    /// Guards that have no setting of their own: true = allowed.
    static var allowsLinkPreviews: Bool { !isOn }
    static var allowsGifWebSearch: Bool { !isOn }
    /// Share / Save to Photos / Save to Files on a received or sent attachment.
    static var allowsMediaExport: Bool { !isOn }

    /// What the environment allows the mode to force.
    struct Environment: Equatable {
        /// The device can authenticate its owner (passcode / Face ID / Touch ID).
        var deviceAuthAvailable: Bool
        /// The privacy relay has a relay address and a pinned gateway key.
        var privacyRelayConfigured: Bool

        static var current: Environment {
            Environment(deviceAuthAvailable: StrictSecurityMode.deviceAuthAvailable(),
                        privacyRelayConfigured: OHTTPSettings.isConfigured)
        }
    }

    /// Pure: every key the mode forces, with the value it forces.
    static func forcedValues(hideReceipts: Bool, environment: Environment) -> [String: Bool] {
        var v: [String: Bool] = [
            // 1. No copy / forward out of OSHI (+ no text selection on the Mac).
            MessagePrivacy.blockForwardAndCopyKey: true,
            // 4. Third-party keyboards refused; no autocorrect learning.
            KeyboardPrivacy.blockThirdPartyKeyboardsKey: true,
            KeyboardPrivacy.privateKeyboardKey: true,
            // 4. Notifications: no contact or group name (text is never shown).
            MessageBannerPolicy.showNamesKey: false,
            MessageBannerPolicy.previewDefaultsKey: false,
            // 2. App switcher snapshot hidden.
            PrivacyCoverController.defaultsKey: true,
        ]
        // 4. App lock, re-locked the moment OSHI leaves the screen. Only when the
        // device can actually authenticate — forcing a lock nobody can open would
        // lock the owner out of their own messages.
        if environment.deviceAuthAvailable {
            v[biometricKey] = true
            v[autoLockKey] = true
        }
        if hideReceipts {
            v[readReceiptsKey] = false
            v[typingIndicatorsKey] = false
        }
        if environment.privacyRelayConfigured {
            v[OHTTPSettings.enabledKey] = true
        }
        return v
    }

    // MARK: Pure core (UserDefaults injected, unit-tested)

    static func savedValues(_ d: UserDefaults) -> [String: Any] {
        d.dictionary(forKey: savedValuesKey) ?? [:]
    }

    /// True while the mode holds `key` at its forced value (the settings screens
    /// grey that toggle out).
    static func isForcing(_ key: String, _ d: UserDefaults = .standard) -> Bool {
        isOn(d) && savedValues(d)[key] != nil
    }

    /// Records the current value of `key` (once) and writes the forced value.
    private static func force(_ key: String, _ value: Bool, in d: UserDefaults, saved: inout [String: Any]) {
        if saved[key] == nil {
            saved[key] = d.object(forKey: key) ?? absentMarker
        }
        d.set(value, forKey: key)
    }

    /// Puts `key` back to what it was before the mode forced it.
    private static func restore(_ key: String, in d: UserDefaults, saved: inout [String: Any]) {
        guard let previous = saved.removeValue(forKey: key) else { return }
        if let s = previous as? String, s == absentMarker {
            d.removeObject(forKey: key)
        } else {
            d.set(previous, forKey: key)
        }
    }

    /// Turns the mode on and forces every setting. Idempotent: a second call
    /// keeps the FIRST snapshot (the values from before the mode).
    static func enable(_ d: UserDefaults, environment: Environment) {
        var saved = savedValues(d)
        for (key, value) in forcedValues(hideReceipts: hidesReceipts(d), environment: environment) {
            force(key, value, in: d, saved: &saved)
        }
        d.set(saved, forKey: savedValuesKey)
        d.set(true, forKey: enabledKey)
    }

    /// Turns the mode off and restores every forced setting.
    static func disable(_ d: UserDefaults) {
        var saved = savedValues(d)
        for key in Array(saved.keys) { restore(key, in: d, saved: &saved) }
        d.removeObject(forKey: savedValuesKey)
        d.set(false, forKey: enabledKey)
    }

    /// The receipts sub-toggle, while the mode is on: force or restore those two.
    static func setHideReceipts(_ hide: Bool, _ d: UserDefaults) {
        d.set(hide, forKey: hideReceiptsKey)
        guard isOn(d) else { return }
        var saved = savedValues(d)
        for key in [readReceiptsKey, typingIndicatorsKey] {
            if hide { force(key, false, in: d, saved: &saved) } else { restore(key, in: d, saved: &saved) }
        }
        d.set(saved, forKey: savedValuesKey)
    }

    // MARK: App-facing entry points (side effects)

    @MainActor
    static func turnOn() {
        enable(.standard, environment: .current)
        applySideEffects()
        clearClipboardIfOSHIWroteIt()
        deleteSystemDonations()
    }

    @MainActor
    static func turnOff() {
        disable(.standard)
        applySideEffects()
    }

    @MainActor
    static func updateHideReceipts(_ hide: Bool) {
        setHideReceipts(hide, .standard)
        NotificationCenter.default.post(name: didChangeNotification, object: nil)
    }

    /// Every forced setting whose consumer caches it or lives in another process.
    @MainActor
    private static func applySideEffects() {
        MessageBannerPolicy.mirrorShowsNames()                 // the notification extension
        _ = OHTTPSettings.userEnabled                          // mirrors the relay switch to the app group
        NotificationManager.shared.setupNotificationCategories() // inline reply vs open-the-app
        WatchBridgeService.requestSnapshotPush()               // the watch reads the app lock
        NotificationCenter.default.post(name: didChangeNotification, object: nil)
        StrictCaptureShield.shared.refresh()
    }

    // MARK: Device authentication

    static func deviceAuthAvailable() -> Bool {
        var error: NSError?
        return LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: &error)
    }

    /// Asks for Face ID / Touch ID / the device passcode. When the device has
    /// no way to authenticate its owner there is no lock to ask for: true.
    static func authenticate(reason: String) async -> Bool {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else { return true }
        return (try? await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) ?? false
    }

    // MARK: Clipboard

    /// Clears the general pasteboard when its current content is the last thing
    /// OSHI wrote there (message text, recovery key…). Anything copied since —
    /// by the person, by another app — has moved `changeCount` and is left alone.
    static func clearClipboardIfOSHIWroteIt(_ pasteboard: UIPasteboard = .general) {
        guard let stamp = SecureClipboard.lastWrittenChangeCount,
              pasteboard.changeCount == stamp else { return }
        pasteboard.items = []
        SecureClipboard.lastWrittenChangeCount = nil
    }

    // MARK: System donations (Siri, Spotlight, Handoff)

    /// OSHI donates nothing to Siri, Spotlight or Handoff (no INInteraction, no
    /// CSSearchableItem, no NSUserActivity anywhere in the app). Turning the mode
    /// on still wipes whatever the system may hold for OSHI, so "nothing" is
    /// true for the past as well — and a future donation site has a switch to read.
    static func deleteSystemDonations() {
        CSSearchableIndex.default().deleteAllSearchableItems { _ in }
        INInteraction.deleteAll { _ in }
        NSUserActivity.deleteAllSavedUserActivities {}
    }

    /// For any future donation site: never donate message content while on.
    static var allowsSystemDonations: Bool { !isOn }
}

// MARK: - Text fields: copy / paste / drag / Writing Tools

/// One switch for every text field and text view in OSHI (SwiftUI's TextField
/// and TextEditor are UITextField / UITextView underneath, on iPhone and Mac).
///
/// SwiftUI has no API to refuse paste in a TextField, so the standard-edit
/// actions are filtered where UIKit asks for them: `canPerformAction(_:withSender:)`
/// drives the edit menu, the iPad shortcut bar and ⌘C / ⌘V / ⌘X on a hardware
/// keyboard and on the Mac. `paste(_:)` itself is also refused, for a caller
/// that skips the check.
enum StrictTextInputGuard {
    private static var installed = false

    /// Pure decision, unit-tested.
    static func refuses(_ action: Selector, strict: Bool, blockPaste: Bool) -> Bool {
        guard strict else { return false }
        let name = NSStringFromSelector(action)
        if copyLikeActions.contains(name) { return true }
        if blockPaste && pasteLikeActions.contains(name) { return true }
        return false
    }

    /// Text leaves OSHI through these: the clipboard, the share sheet, Look Up
    /// (a web search), Writing Tools.
    static let copyLikeActions: Set<String> = [
        "copy:", "cut:", "_share:", "share:", "_lookup:", "_define:",
        "showWritingTools:", "_startWritingTools:",
    ]
    static let pasteLikeActions: Set<String> = [
        "paste:", "pasteAndMatchStyle:", "pasteAndGo:", "pasteAndSearch:",
    ]

    static func install() {
        guard !installed else { return }
        installed = true
        for cls in [UITextField.self, UITextView.self] as [AnyClass] {
            swizzle(cls, #selector(UIResponder.canPerformAction(_:withSender:)),
                    #selector(UIView.oshiStrict_canPerformAction(_:withSender:)))
            swizzle(cls, #selector(UIResponderStandardEditActions.paste(_:)),
                    #selector(UIView.oshiStrict_paste(_:)))
            swizzle(cls, #selector(UIView.didMoveToWindow),
                    #selector(UIView.oshiStrict_didMoveToWindow))
        }
    }

    /// Exchanges on `cls` only. Both methods are first copied down onto `cls`
    /// (a no-op when it already has its own), so the exchange never touches a
    /// superclass: UILabel, UIButton and the rest never see the replacement.
    private static func swizzle(_ cls: AnyClass, _ original: Selector, _ replacement: Selector) {
        guard let o = class_getInstanceMethod(cls, original),
              let r = class_getInstanceMethod(cls, replacement) else { return }
        class_addMethod(cls, original, method_getImplementation(o), method_getTypeEncoding(o))
        class_addMethod(cls, replacement, method_getImplementation(r), method_getTypeEncoding(r))
        guard let own = class_getInstanceMethod(cls, original),
              let ownReplacement = class_getInstanceMethod(cls, replacement) else { return }
        method_exchangeImplementations(own, ownReplacement)
    }

    /// Drag out, drop in, Writing Tools and inline predictions, set when the
    /// field appears (chat screens are rebuilt after the switch changes).
    static func apply(to view: UIView) {
        guard StrictSecurityMode.isOn else {
            if let droppable = view as? UITextDroppable,
               droppable.textDropDelegate === StrictTextDropBlocker.shared {
                droppable.textDropDelegate = nil
            }
            return
        }
        if let draggable = view as? UITextDraggable {
            draggable.textDragInteraction?.isEnabled = false
        }
        if StrictSecurityMode.blocksPaste(), let droppable = view as? UITextDroppable {
            droppable.textDropDelegate = StrictTextDropBlocker.shared
        }
        if let field = view as? UITextField {
            field.inlinePredictionType = .no
            if #available(iOS 18.0, macCatalyst 18.0, *) { field.writingToolsBehavior = .none }
        } else if let textView = view as? UITextView {
            textView.inlinePredictionType = .no
            if #available(iOS 18.0, macCatalyst 18.0, *) { textView.writingToolsBehavior = .none }
        }
    }
}

/// Refuses every drop into a text field while paste is blocked.
final class StrictTextDropBlocker: NSObject, UITextDropDelegate {
    static let shared = StrictTextDropBlocker()

    func textDroppableView(_ textDroppableView: UIView & UITextDroppable,
                           proposalForDrop drop: UITextDropRequest) -> UITextDropProposal {
        UITextDropProposal(operation: .forbidden)
    }
}

extension UIView {
    // Swizzled onto UITextField / UITextView only (see StrictTextInputGuard).
    // After the exchange, calling the `oshiStrict_` name runs the ORIGINAL.

    @objc func oshiStrict_canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        if StrictTextInputGuard.refuses(action, strict: StrictSecurityMode.isOn,
                                        blockPaste: StrictSecurityMode.blocksPaste()) {
            return false
        }
        return oshiStrict_canPerformAction(action, withSender: sender)
    }

    @objc func oshiStrict_paste(_ sender: Any?) {
        if StrictSecurityMode.blocksPaste() { return }
        oshiStrict_paste(sender)
    }

    @objc func oshiStrict_didMoveToWindow() {
        oshiStrict_didMoveToWindow()
        if window != nil { StrictTextInputGuard.apply(to: self) }
    }
}

// MARK: - SwiftUI: Writing Tools opt-out

private struct StrictWritingToolsOptOut: ViewModifier {
    @AppStorage(StrictSecurityMode.enabledKey) private var strict = false

    func body(content: Content) -> some View {
        if strict {
            if #available(iOS 18.0, macCatalyst 18.0, *) {
                content.writingToolsBehavior(.disabled)
            } else {
                content
            }
        } else {
            content
        }
    }
}

extension View {
    /// Apple Intelligence Writing Tools never see this text while strict mode is on.
    func strictWritingToolsOptOut() -> some View {
        modifier(StrictWritingToolsOptOut())
    }
}

// MARK: - Screen capture / mirroring shield (iPhone, iPad)

/// The secure-layer trick (`secureScreen()`) already blanks OSHI in screenshots
/// and recordings. Strict mode adds a visible cover whenever iOS reports the
/// screen as captured — recording, AirPlay / cable mirroring, QuickTime, a
/// second display — so the content is not readable on the OTHER screen either,
/// and the person is told why.
final class StrictCaptureShield {
    static let shared = StrictCaptureShield()
    private var covers: [ObjectIdentifier: UIWindow] = [:]
    private var installed = false

    /// Pure decision, unit-tested.
    static func shouldCover(strict: Bool, captured: Bool, externalScreens: Int) -> Bool {
        strict && (captured || externalScreens > 0)
    }

    func install() {
        #if !targetEnvironment(macCatalyst)
        guard !installed else { return }
        installed = true
        let nc = NotificationCenter.default
        let names: [Notification.Name] = [
            UIScreen.capturedDidChangeNotification,
            UIScene.willConnectNotification,
            UIScene.didDisconnectNotification,
            UIScene.didActivateNotification,
        ]
        for name in names {
            nc.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in self?.refresh() }
        }
        refresh()
        #endif
    }

    func refresh() {
        #if !targetEnvironment(macCatalyst)
        let strict = StrictSecurityMode.isOn
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        // A cable / AirPlay display that is NOT a mirror shows up as its own
        // non-interactive scene; a mirror shows up as `isCaptured`.
        let external = scenes.filter { $0.session.role == .windowExternalDisplayNonInteractive }.count
        for scene in scenes where scene.session.role == .windowApplication {
            let cover = Self.shouldCover(strict: strict, captured: scene.screen.isCaptured,
                                         externalScreens: external)
            if cover { show(on: scene) } else { hide(scene) }
        }
        #endif
    }

    private func show(on scene: UIWindowScene) {
        let id = ObjectIdentifier(scene)
        guard covers[id] == nil else { return }
        let window = UIWindow(windowScene: scene)
        window.windowLevel = UIWindow.Level.alert + 2
        let host = UIHostingController(rootView: StrictCaptureCoverView())
        window.rootViewController = host
        window.isHidden = false
        covers[id] = window
    }

    private func hide(_ scene: UIScene) {
        guard let window = covers.removeValue(forKey: ObjectIdentifier(scene)) else { return }
        window.isHidden = true
        window.rootViewController = nil
    }
}

private struct StrictCaptureCoverView: View {
    var body: some View {
        ZStack {
            Rectangle().fill(.ultraThickMaterial).ignoresSafeArea()
            VStack(spacing: 14) {
                Image(systemName: "rectangle.on.rectangle.slash")
                    .font(.system(size: 44))
                    .foregroundColor(.secondary)
                    .accessibilityHidden(true)
                Text("strict_capture_title".localized)
                    .font(.headline)
                    .multilineTextAlignment(.center)
                Text("strict_capture_body".localized)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(32)
        }
        .accessibilityIdentifier("strict.captureCover")
    }
}

// MARK: - Remote control / automation detection (Mac)

/// What macOS lets a sandboxed app see: the list of running applications. A
/// remote-desktop host or an AI agent that drives the screen is a running app;
/// OSHI cannot stop it (macOS gives the decision to the person, in System
/// Settings › Privacy & Security › Accessibility / Screen Recording), but it can
/// SAY it is there. iPhone and iPad have no such list — and no third-party
/// remote-control or accessibility software to begin with.
enum RemoteControlDetector {
    struct Tool: Equatable, Hashable {
        let name: String
        let kind: Kind
        enum Kind: String { case remoteDesktop, aiAgent, automation }
    }

    /// Bundle-identifier prefixes, lower-cased. A prefix covers the host, the
    /// helper and the "QuickSupport"-style variants a vendor ships.
    static let knownTools: [(prefix: String, tool: Tool)] = [
        ("com.teamviewer.", Tool(name: "TeamViewer", kind: .remoteDesktop)),
        ("com.philandro.anydesk", Tool(name: "AnyDesk", kind: .remoteDesktop)),
        ("com.carriez.rustdesk", Tool(name: "RustDesk", kind: .remoteDesktop)),
        ("com.rustdesk.", Tool(name: "RustDesk", kind: .remoteDesktop)),
        ("com.google.chromeremotedesktop", Tool(name: "Chrome Remote Desktop", kind: .remoteDesktop)),
        ("com.google.chrome.remote_desktop", Tool(name: "Chrome Remote Desktop", kind: .remoteDesktop)),
        ("org.chromium.chromoting", Tool(name: "Chrome Remote Desktop", kind: .remoteDesktop)),
        ("com.realvnc.", Tool(name: "RealVNC", kind: .remoteDesktop)),
        ("com.tightvnc.", Tool(name: "TightVNC", kind: .remoteDesktop)),
        ("net.tigervnc.", Tool(name: "TigerVNC", kind: .remoteDesktop)),
        ("com.apple.screensharing.agent", Tool(name: "Screen Sharing", kind: .remoteDesktop)),
        ("com.apple.remotedesktopagent", Tool(name: "Apple Remote Desktop", kind: .remoteDesktop)),
        ("com.splashtop.", Tool(name: "Splashtop", kind: .remoteDesktop)),
        ("com.p5sys.jump.connect", Tool(name: "Jump Desktop Connect", kind: .remoteDesktop)),
        ("tv.parsec.", Tool(name: "Parsec", kind: .remoteDesktop)),
        ("com.logmein.", Tool(name: "LogMeIn", kind: .remoteDesktop)),
        ("com.screenconnect.", Tool(name: "ScreenConnect", kind: .remoteDesktop)),
        ("com.connectwise.", Tool(name: "ConnectWise Control", kind: .remoteDesktop)),
        ("com.bomgar.", Tool(name: "BeyondTrust Remote Support", kind: .remoteDesktop)),
        ("com.openai.chat", Tool(name: "ChatGPT", kind: .aiAgent)),
        ("com.openai.atlas", Tool(name: "ChatGPT Atlas", kind: .aiAgent)),
        ("com.anthropic.claudefordesktop", Tool(name: "Claude", kind: .aiAgent)),
        ("ai.perplexity.", Tool(name: "Perplexity", kind: .aiAgent)),
        ("com.stairways.keyboardmaestro.engine", Tool(name: "Keyboard Maestro", kind: .automation)),
        ("org.hammerspoon.", Tool(name: "Hammerspoon", kind: .automation)),
    ]

    /// Pure: which known tools appear in a list of bundle identifiers.
    static func detect(bundleIdentifiers: [String]) -> [Tool] {
        var found: [Tool] = []
        for id in bundleIdentifiers.map({ $0.lowercased() }) {
            for entry in knownTools where id.hasPrefix(entry.prefix) && !found.contains(entry.tool) {
                found.append(entry.tool)
            }
        }
        return found
    }

    /// Running applications, through AppKit's NSWorkspace (Catalyst reaches it
    /// via the ObjC runtime). Empty on iPhone / iPad.
    static func runningBundleIdentifiers() -> [String] {
        guard let apps = NSClassFromString("NSWorkspace")?
                .value(forKeyPath: "sharedWorkspace.runningApplications") as? [NSObject] else { return [] }
        return apps.compactMap { $0.value(forKey: "bundleIdentifier") as? String }
    }

    static func detectRunning() -> [Tool] {
        detect(bundleIdentifiers: runningBundleIdentifiers())
    }
}

// MARK: - Root modifier

/// Strict-mode duties that need the app's root: count the authentication made
/// to turn the mode on as an unlock, and on the Mac warn (once per new set of
/// tools) when remote-control or AI-agent software is running.
private struct StrictSecurityRoot: ViewModifier {
    @AppStorage(StrictSecurityMode.enabledKey) private var strict = false
    @State private var detected: [RemoteControlDetector.Tool] = []
    @State private var warnedSet: Set<RemoteControlDetector.Tool> = []
    @State private var showWarning = false
    let onAuthenticated: () -> Void

    func body(content: Content) -> some View {
        content
            .onReceive(NotificationCenter.default.publisher(for: StrictSecurityMode.didAuthenticateNotification)) { _ in
                onAuthenticated()
            }
            .onReceive(NotificationCenter.default.publisher(for: UIScene.didActivateNotification)) { _ in
                check()
            }
            .onChange(of: strict) { _, _ in check() }
            .alert("strict_remote_alert_title".localized, isPresented: $showWarning) {
                Button("common.ok".localized, role: .cancel) {}
            } message: {
                Text(String(format: "strict_remote_alert_body".localized,
                            detected.map(\.name).joined(separator: ", ")))
            }
    }

    private func check() {
        #if targetEnvironment(macCatalyst)
        guard strict else { warnedSet = []; return }
        let tools = RemoteControlDetector.detectRunning()
        detected = tools
        let set = Set(tools)
        if !set.isEmpty && !set.isSubset(of: warnedSet) {
            warnedSet = set
            showWarning = true
        }
        #endif
    }
}

extension View {
    func strictSecurityRoot(onAuthenticated: @escaping () -> Void) -> some View {
        modifier(StrictSecurityRoot(onAuthenticated: onAuthenticated))
    }
}
