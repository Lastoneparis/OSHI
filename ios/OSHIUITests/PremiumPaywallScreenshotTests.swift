//
//  PremiumPaywallScreenshotTests.swift
//  OSHIUITests
//
//  Captures the App Store Connect review screenshot for the two OSHI Premium
//  subscriptions. Apple holds both products in MISSING_METADATA until each has
//  one, so this is the last gate before they can be submitted.
//
//  Why a UI test rather than `simctl launch` + `simctl io screenshot`:
//  a scheme's StoreKit configuration is applied by XCODE when it launches the
//  app. A bare `simctl launch` ignores it, `Product.products(for:)` returns an
//  empty array, and the paywall correctly renders "Plans aren't available right
//  now" — a truthful screenshot of nothing, and useless to a reviewer.
//  `SKTestSession` sets up that same environment from the test process, so the
//  app under test loads the real product IDs at the real prices.
//
//  The shot therefore shows the actual paywall with actual StoreKit prices. It
//  is not a mockup.
//

import XCTest
import StoreKitTest

final class PremiumPaywallScreenshotTests: XCTestCase {

    private var session: SKTestSession!

    override func setUpWithError() throws {
        continueAfterFailure = false
        // If the .storekit file is not a RESOURCE of this test target, this
        // throws "Code=4 File not found" — which reads like a missing product
        // rather than a missing build-phase entry.
        session = try SKTestSession(configurationFileNamed: "OSHIPremium")
        session.resetToDefaultState()
        session.disableDialogs = true
        session.clearTransactions()
    }

    override func tearDownWithError() throws {
        session = nil
    }

    func testCapturePaywallForReview() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-OSHIScreens", "-OSHIPaywall"]
        app.launchArguments += ["-hasSeenOnboarding", "YES", "-callKitDisabled", "YES"]
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()

        // The hook presents the sheet 1s after the tab UI appears; products then
        // have to come back from StoreKit. Wait on the CONTENT rather than
        // sleeping, so a slow simulator does not silently produce a shot of a
        // spinner.
        let upgrade = app.buttons["Upgrade"]
        let priced = app.staticTexts["$2.99"]
        let appeared = priced.waitForExistence(timeout: 30) || upgrade.waitForExistence(timeout: 10)

        // Capture regardless: a failed wait is far more diagnosable with the
        // screenshot attached than without it.
        let shot = app.screenshot()
        let att = XCTAttachment(screenshot: shot)
        att.name = "premium-paywall-review"
        att.lifetime = .keepAlways
        add(att)

        // Also write it somewhere a script can pick it up without unpacking an
        // .xcresult bundle.
        let out = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("premium-paywall-review.png")
        try? shot.pngRepresentation.write(to: out)
        print("PAYWALL_SHOT_PATH=\(out.path)")

        XCTAssertTrue(appeared, "The paywall never showed a price — StoreKit products did not load.")
        XCTAssertTrue(app.staticTexts["$24.90"].exists, "The yearly price is missing from the paywall.")

        // Apple requires both of these on an auto-renewable paywall (3.1.2), and
        // the first capture shipped without either: the app's AccentColor asset
        // is white in BOTH appearances, so an untinted Button label or Link is
        // white-on-white — present in the hierarchy, invisible on screen.
        // `isHittable` is the assertion that catches that; `exists` would not.
        XCTAssertTrue(app.buttons["Restore purchases"].isHittable,
                      "Restore Purchases is not visible — Apple requires it for auto-renewables.")
        // A SwiftUI Link is exposed as a BUTTON in the accessibility tree, not
        // under `app.links` — querying links here returns nothing and the
        // assertion fails on a paywall that is perfectly correct.
        XCTAssertTrue(visibleControl(app, "Terms of Use"),
                      "The EULA link is not visible — 3.1.2(c) requires it at the point of purchase.")
        XCTAssertTrue(visibleControl(app, "Privacy Policy"),
                      "The Privacy Policy link is not visible.")
    }

    /// True only if the control is on screen and tappable — `exists` alone would
    /// pass for white-on-white text that is in the hierarchy but invisible.
    private func visibleControl(_ app: XCUIApplication, _ label: String) -> Bool {
        for q in [app.buttons[label], app.links[label], app.staticTexts[label]] where q.exists {
            if q.isHittable { return true }
        }
        return false
    }
}
