//
//  StrictSecurityModeTests.swift
//  OSHITests
//
//  __STRICT_SECURITY_MODE_2026_11_22__ persistence, forced settings and their
//  exact restoration, every guard switch, and the detection list.
//

import XCTest
import UIKit
@testable import OSHI

final class StrictSecurityModeTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!

    private let fullEnv = StrictSecurityMode.Environment(deviceAuthAvailable: true, privacyRelayConfigured: true)
    private let bareEnv = StrictSecurityMode.Environment(deviceAuthAvailable: false, privacyRelayConfigured: false)

    override func setUp() {
        super.setUp()
        suiteName = "StrictSecurityModeTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    // MARK: State persistence

    func testOffByDefault() {
        XCTAssertFalse(StrictSecurityMode.isOn(defaults))
        XCTAssertFalse(StrictSecurityMode.blocksPaste(defaults))
    }

    func testEnablePersistsAndForcesEverySetting() {
        StrictSecurityMode.enable(defaults, environment: fullEnv)

        XCTAssertTrue(StrictSecurityMode.isOn(defaults))
        XCTAssertTrue(defaults.bool(forKey: MessagePrivacy.blockForwardAndCopyKey), "copy/forward hidden")
        XCTAssertTrue(defaults.bool(forKey: KeyboardPrivacy.blockThirdPartyKeyboardsKey))
        XCTAssertTrue(defaults.bool(forKey: KeyboardPrivacy.privateKeyboardKey))
        XCTAssertEqual(defaults.object(forKey: MessageBannerPolicy.showNamesKey) as? Bool, false, "no names in banners")
        XCTAssertEqual(defaults.object(forKey: MessageBannerPolicy.previewDefaultsKey) as? Bool, false)
        XCTAssertTrue(defaults.bool(forKey: PrivacyCoverController.defaultsKey), "app switcher cover")
        XCTAssertTrue(defaults.bool(forKey: StrictSecurityMode.biometricKey), "app lock")
        XCTAssertTrue(defaults.bool(forKey: StrictSecurityMode.autoLockKey), "lock on leave")
        XCTAssertEqual(defaults.object(forKey: StrictSecurityMode.readReceiptsKey) as? Bool, false)
        XCTAssertEqual(defaults.object(forKey: StrictSecurityMode.typingIndicatorsKey) as? Bool, false)
        XCTAssertTrue(defaults.bool(forKey: OHTTPSettings.enabledKey), "privacy relay")
    }

    func testDisableRestoresPreviousValuesExactly() {
        // A person with their own choices, some keys never written.
        defaults.set(false, forKey: KeyboardPrivacy.blockThirdPartyKeyboardsKey)
        defaults.set(true, forKey: MessageBannerPolicy.showNamesKey)
        defaults.set(true, forKey: StrictSecurityMode.readReceiptsKey)
        defaults.set(false, forKey: PrivacyCoverController.defaultsKey)
        XCTAssertNil(defaults.object(forKey: MessagePrivacy.blockForwardAndCopyKey))
        XCTAssertNil(defaults.object(forKey: StrictSecurityMode.biometricKey))

        StrictSecurityMode.enable(defaults, environment: fullEnv)
        StrictSecurityMode.disable(defaults)

        XCTAssertFalse(StrictSecurityMode.isOn(defaults))
        XCTAssertEqual(defaults.object(forKey: KeyboardPrivacy.blockThirdPartyKeyboardsKey) as? Bool, false)
        XCTAssertEqual(defaults.object(forKey: MessageBannerPolicy.showNamesKey) as? Bool, true)
        XCTAssertEqual(defaults.object(forKey: StrictSecurityMode.readReceiptsKey) as? Bool, true)
        XCTAssertEqual(defaults.object(forKey: PrivacyCoverController.defaultsKey) as? Bool, false)
        // Never written before → removed again, so the setting's own default applies.
        XCTAssertNil(defaults.object(forKey: MessagePrivacy.blockForwardAndCopyKey))
        XCTAssertNil(defaults.object(forKey: StrictSecurityMode.biometricKey))
        XCTAssertNil(defaults.object(forKey: OHTTPSettings.enabledKey))
        XCTAssertNil(defaults.object(forKey: StrictSecurityMode.savedValuesKey))
    }

    func testEnablingTwiceKeepsTheFirstSnapshot() {
        defaults.set(true, forKey: StrictSecurityMode.typingIndicatorsKey)
        StrictSecurityMode.enable(defaults, environment: fullEnv)
        StrictSecurityMode.enable(defaults, environment: fullEnv)   // must not snapshot the forced `false`
        StrictSecurityMode.disable(defaults)
        XCTAssertEqual(defaults.object(forKey: StrictSecurityMode.typingIndicatorsKey) as? Bool, true)
    }

    func testNoAppLockForcedWhenTheDeviceCannotAuthenticate() {
        StrictSecurityMode.enable(defaults, environment: bareEnv)
        XCTAssertNil(defaults.object(forKey: StrictSecurityMode.biometricKey),
                     "forcing a lock nobody can open would lock the owner out")
        XCTAssertNil(defaults.object(forKey: StrictSecurityMode.autoLockKey))
        XCTAssertNil(defaults.object(forKey: OHTTPSettings.enabledKey), "relay not configured: untouched")
    }

    func testIsForcingOnlyWhileOn() {
        XCTAssertFalse(StrictSecurityMode.isForcing(MessagePrivacy.blockForwardAndCopyKey, defaults))
        StrictSecurityMode.enable(defaults, environment: bareEnv)
        XCTAssertTrue(StrictSecurityMode.isForcing(MessagePrivacy.blockForwardAndCopyKey, defaults))
        XCTAssertFalse(StrictSecurityMode.isForcing("lastSeenEnabled", defaults), "not a forced setting")
        StrictSecurityMode.disable(defaults)
        XCTAssertFalse(StrictSecurityMode.isForcing(MessagePrivacy.blockForwardAndCopyKey, defaults))
    }

    // MARK: Sub-toggles

    func testReceiptsSubToggleOffLeavesReceiptsAlone() {
        defaults.set(true, forKey: StrictSecurityMode.readReceiptsKey)
        StrictSecurityMode.setHideReceipts(false, defaults)
        StrictSecurityMode.enable(defaults, environment: bareEnv)
        XCTAssertEqual(defaults.object(forKey: StrictSecurityMode.readReceiptsKey) as? Bool, true)
        XCTAssertFalse(StrictSecurityMode.isForcing(StrictSecurityMode.readReceiptsKey, defaults))
    }

    func testReceiptsSubToggleWhileOnForcesThenRestores() {
        defaults.set(true, forKey: StrictSecurityMode.readReceiptsKey)
        StrictSecurityMode.enable(defaults, environment: bareEnv)
        XCTAssertEqual(defaults.object(forKey: StrictSecurityMode.readReceiptsKey) as? Bool, false)

        StrictSecurityMode.setHideReceipts(false, defaults)
        XCTAssertEqual(defaults.object(forKey: StrictSecurityMode.readReceiptsKey) as? Bool, true)
        XCTAssertNil(defaults.object(forKey: StrictSecurityMode.typingIndicatorsKey))

        StrictSecurityMode.setHideReceipts(true, defaults)
        XCTAssertEqual(defaults.object(forKey: StrictSecurityMode.readReceiptsKey) as? Bool, false)
        StrictSecurityMode.disable(defaults)
        XCTAssertEqual(defaults.object(forKey: StrictSecurityMode.readReceiptsKey) as? Bool, true)
    }

    func testBlockPasteDefaultsOnAndFollowsTheSubToggle() {
        StrictSecurityMode.enable(defaults, environment: bareEnv)
        XCTAssertTrue(StrictSecurityMode.blocksPaste(defaults))
        defaults.set(false, forKey: StrictSecurityMode.blockPasteKey)
        XCTAssertFalse(StrictSecurityMode.blocksPaste(defaults))
    }

    // MARK: Guards

    func testCopyLikeActionsRefusedOnlyInStrictMode() {
        for name in ["copy:", "cut:", "_share:", "_lookup:"] {
            let sel = NSSelectorFromString(name)
            XCTAssertTrue(StrictTextInputGuard.refuses(sel, strict: true, blockPaste: false), name)
            XCTAssertFalse(StrictTextInputGuard.refuses(sel, strict: false, blockPaste: true), name)
        }
    }

    func testPasteRefusedOnlyWithTheSubToggle() {
        let paste = #selector(UIResponderStandardEditActions.paste(_:))
        XCTAssertTrue(StrictTextInputGuard.refuses(paste, strict: true, blockPaste: true))
        XCTAssertFalse(StrictTextInputGuard.refuses(paste, strict: true, blockPaste: false))
        XCTAssertFalse(StrictTextInputGuard.refuses(paste, strict: false, blockPaste: true))
        XCTAssertTrue(StrictTextInputGuard.refuses(NSSelectorFromString("pasteAndMatchStyle:"), strict: true, blockPaste: true))
    }

    func testEditingActionsStayAvailable() {
        for name in ["select:", "selectAll:", "delete:", "_translate:"] {
            XCTAssertFalse(StrictTextInputGuard.refuses(NSSelectorFromString(name), strict: true, blockPaste: true), name)
        }
    }

    /// End to end through the swizzled UIKit method, not just the pure rule.
    func testRealTextFieldRefusesPasteAndCopyWhileStrict() {
        StrictTextInputGuard.install()
        let field = UITextField()
        field.text = "secret"
        let standard = UserDefaults.standard
        let previous = standard.object(forKey: StrictSecurityMode.enabledKey)
        defer {
            if let previous { standard.set(previous, forKey: StrictSecurityMode.enabledKey) }
            else { standard.removeObject(forKey: StrictSecurityMode.enabledKey) }
        }
        standard.set(true, forKey: StrictSecurityMode.enabledKey)
        XCTAssertFalse(field.canPerformAction(#selector(UIResponderStandardEditActions.copy(_:)), withSender: nil))
        XCTAssertFalse(field.canPerformAction(#selector(UIResponderStandardEditActions.paste(_:)), withSender: nil))
        let textView = UITextView()
        XCTAssertFalse(textView.canPerformAction(#selector(UIResponderStandardEditActions.paste(_:)), withSender: nil))
        // Off again: UIKit decides (no forced `false` left behind).
        standard.set(false, forKey: StrictSecurityMode.enabledKey)
        XCTAssertEqual(field.canPerformAction(#selector(UIResponderStandardEditActions.selectAll(_:)), withSender: nil),
                       UITextField().canPerformAction(#selector(UIResponderStandardEditActions.selectAll(_:)), withSender: nil))
    }

    func testCaptureCoverDecision() {
        XCTAssertTrue(StrictCaptureShield.shouldCover(strict: true, captured: true, externalScreens: 0))
        XCTAssertTrue(StrictCaptureShield.shouldCover(strict: true, captured: false, externalScreens: 1))
        XCTAssertFalse(StrictCaptureShield.shouldCover(strict: true, captured: false, externalScreens: 0))
        XCTAssertFalse(StrictCaptureShield.shouldCover(strict: false, captured: true, externalScreens: 1))
    }

    func testClipboardClearedOnlyWhenOSHIWroteTheCurrentContent() {
        let pb = UIPasteboard.withUniqueName()
        defer { UIPasteboard.remove(withName: pb.name) }
        pb.string = "from OSHI"
        SecureClipboard.lastWrittenChangeCount = pb.changeCount
        StrictSecurityMode.clearClipboardIfOSHIWroteIt(pb)
        XCTAssertFalse(pb.hasStrings, "OSHI's own copy is cleared")

        pb.string = "copied by the person elsewhere"
        SecureClipboard.lastWrittenChangeCount = pb.changeCount - 1
        StrictSecurityMode.clearClipboardIfOSHIWroteIt(pb)
        XCTAssertEqual(pb.string, "copied by the person elsewhere", "a newer copy is never eaten")
        SecureClipboard.lastWrittenChangeCount = nil
    }

    func testFeatureGatesFollowTheMode() {
        let standard = UserDefaults.standard
        let previous = standard.object(forKey: StrictSecurityMode.enabledKey)
        defer {
            if let previous { standard.set(previous, forKey: StrictSecurityMode.enabledKey) }
            else { standard.removeObject(forKey: StrictSecurityMode.enabledKey) }
        }
        standard.set(true, forKey: StrictSecurityMode.enabledKey)
        XCTAssertFalse(StrictSecurityMode.allowsLinkPreviews)
        XCTAssertFalse(StrictSecurityMode.allowsGifWebSearch)
        XCTAssertFalse(GiphyClient.isConfigured, "GIPHY web search off")
        XCTAssertFalse(StrictSecurityMode.allowsMediaExport)
        XCTAssertFalse(StrictSecurityMode.allowsSystemDonations)
        standard.set(false, forKey: StrictSecurityMode.enabledKey)
        XCTAssertTrue(StrictSecurityMode.allowsLinkPreviews)
        XCTAssertTrue(GiphyClient.isConfigured)
        XCTAssertTrue(StrictSecurityMode.allowsMediaExport)
    }

    func testLinkPreviewFetcherShowsNothingWhileStrict() {
        let standard = UserDefaults.standard
        let previous = standard.object(forKey: StrictSecurityMode.enabledKey)
        defer {
            if let previous { standard.set(previous, forKey: StrictSecurityMode.enabledKey) }
            else { standard.removeObject(forKey: StrictSecurityMode.enabledKey) }
        }
        standard.set(true, forKey: StrictSecurityMode.enabledKey)
        let fetcher = LinkPreviewFetcher()
        fetcher.fetchPreview(for: "https://example.com", allowNetwork: true)
        XCTAssertNil(fetcher.previewData)
        XCTAssertFalse(fetcher.isLoading, "no request started")
    }

    // MARK: Detection list (Mac)

    func testDetectsKnownRemoteControlAndAgentApps() {
        let found = RemoteControlDetector.detect(bundleIdentifiers: [
            "com.apple.Safari",
            "com.teamviewer.TeamViewer",
            "com.teamviewer.TeamViewerHost",     // same vendor: listed once
            "com.philandro.anydesk",
            "com.carriez.RustDesk",              // case-insensitive
            "com.google.chromeremotedesktop.me2me-host",
            "com.realvnc.vncserver",
            "com.apple.screensharing.agent",
            "com.openai.chat",
            "com.anthropic.claudefordesktop",
        ])
        XCTAssertEqual(found.map(\.name), [
            "TeamViewer", "AnyDesk", "RustDesk", "Chrome Remote Desktop",
            "RealVNC", "Screen Sharing", "ChatGPT", "Claude",
        ])
        XCTAssertEqual(found.first { $0.name == "Claude" }?.kind, .aiAgent)
    }

    func testOrdinaryAppsAreNotFlagged() {
        XCTAssertTrue(RemoteControlDetector.detect(bundleIdentifiers: [
            "com.apple.Safari", "com.apple.ScreenSharing.viewer-like", "com.microsoft.teams2",
            "com.google.Chrome", "org.mozilla.firefox",
        ]).isEmpty)
    }

    func testDetectionIsEmptyOnIPhone() {
        #if !targetEnvironment(macCatalyst)
        XCTAssertTrue(RemoteControlDetector.runningBundleIdentifiers().isEmpty)
        #endif
    }

    // MARK: Strings

    func testEveryStrictStringExistsInEveryLocale() throws {
        let bundle = Bundle(for: OshiAppDelegate.self)
        let en = try XCTUnwrap(bundle.path(forResource: "Localizable", ofType: "strings", inDirectory: nil, forLocalization: "en"))
        let enKeys = (NSDictionary(contentsOfFile: en) as? [String: String] ?? [:]).keys.filter { $0.hasPrefix("strict_") }
        XCTAssertGreaterThanOrEqual(enKeys.count, 40)
        for loc in bundle.localizations where loc != "Base" {
            guard let path = bundle.path(forResource: "Localizable", ofType: "strings", inDirectory: nil, forLocalization: loc),
                  let dict = NSDictionary(contentsOfFile: path) as? [String: String] else { continue }
            for key in enKeys {
                XCTAssertNotNil(dict[key], "\(loc) is missing \(key)")
            }
            XCTAssertTrue(dict["strict_remote_alert_body"]?.contains("%@") ?? false, "\(loc) placeholder")
        }
    }
}
