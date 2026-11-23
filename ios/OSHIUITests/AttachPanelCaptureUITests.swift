import XCTest

/// __ATTACH_PANEL_2027_2026_11_17__ Drives the attachment panel in a 1:1 and a
/// group and saves screenshots to `$ATTACH_SHOT_DIR` (default
/// /tmp/oshi-attach-shots), names prefixed by `$ATTACH_SHOT_PREFIX` (e.g.
/// "light", "oled"). The photo-library permission is set from outside
/// (`simctl privacy`), so the same tests capture the full / no-access states;
/// `testLimitedAccessFlow` walks the real system alert to "Limit Access".
final class AttachPanelCaptureUITests: XCTestCase {

    override func setUpWithError() throws { continueAfterFailure = true }

    private var env: [String: String] { ProcessInfo.processInfo.environment }
    private var shotDir: String { env["ATTACH_SHOT_DIR"] ?? "/tmp/oshi-attach-shots" }
    private var prefix: String { env["ATTACH_SHOT_PREFIX"].map { $0 + "-" } ?? "" }

    private func shot(_ app: XCUIApplication, _ name: String) {
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try? FileManager.default.createDirectory(atPath: shotDir, withIntermediateDirectories: true)
        try? png.write(to: URL(fileURLWithPath: shotDir).appendingPathComponent(prefix + name + ".png"))
        let a = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        a.name = prefix + name; a.lifetime = .keepAlways; add(a)
    }

    private func launch(group: Bool) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-OSHIScreens", group ? "-OSHIOpenGroup" : "-OSHIOpenChat",
                                "-AppleLanguages", "(en)", "-AppleLocale", "en_US"] + MascotCaptureUITests.extraArgs
        app.launch()
        let composer = app.descendants(matching: .any)[group ? "group.composer" : "chat.composer"].firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 25), "chat did not open")
        sleep(2)
        return app
    }

    private func openPanel(_ app: XCUIApplication) {
        let plus = app.buttons["composer.attach"].firstMatch
        XCTAssertTrue(plus.waitForExistence(timeout: 5), "no attach button")
        plus.tap()
        XCTAssertTrue(app.descendants(matching: .any)["attach.title"].firstMatch.waitForExistence(timeout: 5),
                      "panel did not open")
        sleep(1)
        // `ATTACH_GRANT=full`: answer the real permission alert with full access
        // (the runner reinstalls the app, which resets `simctl privacy`).
        if env["ATTACH_GRANT"] == "full" {
            let allow = app.buttons["attach.allowAccess"].firstMatch
            if allow.exists {
                allow.tap()
                let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
                let full = springboard.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Allow Full Access'")).firstMatch
                if full.waitForExistence(timeout: 8) { full.tap() }
                sleep(2)
            }
        } else if env["ATTACH_GRANT"] == "deny" {
            // Refuse once: the card must turn into "Open Settings".
            let allow = app.buttons["attach.allowAccess"].firstMatch
            if allow.exists {
                shot(app, "noaccess")
                allow.tap()
                let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
                let no = springboard.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Don'")).firstMatch
                if no.waitForExistence(timeout: 8) { no.tap() }
                sleep(2)
                XCTAssertTrue(app.buttons["attach.openSettings"].firstMatch.waitForExistence(timeout: 5),
                              "denied state must offer Open Settings")
                shot(app, "denied")
            }
        }
    }

    private func asset(_ app: XCUIApplication, _ i: Int) -> XCUIElement {
        app.buttons["attach.asset.\(i)"].firstMatch
    }

    /// Collapsed → expanded → multi-select (badges 1·2·3) → deselect #1
    /// (renumber) → caption → send options. Shots at each step.
    private func walk(_ app: XCUIApplication, tag: String, sendOptions: Bool) {
        openPanel(app)
        shot(app, "\(tag)-collapsed")
        let options = app.descendants(matching: .any)["attach.options"].firstMatch
        if options.exists {
            options.swipeLeft(); sleep(1)
            shot(app, "\(tag)-options-scrolled")
            options.swipeRight(); sleep(1)
        }
        guard asset(app, 0).waitForExistence(timeout: 4) else {
            // No-access card: nothing else to walk.
            return
        }
        app.descendants(matching: .any)["attach.title"].firstMatch.swipeUp()
        sleep(1)
        let grid = asset(app, 4)
        if grid.exists { grid.swipeUp(velocity: .slow) }
        sleep(1)
        shot(app, "\(tag)-expanded")
        for i in [0, 2, 3] where asset(app, i).exists { asset(app, i).tap(); usleep(250_000) }
        sleep(1)
        shot(app, "\(tag)-multiselect")
        asset(app, 0).tap(); sleep(1)
        shot(app, "\(tag)-renumbered")
        asset(app, 0).tap(); sleep(1)
        let collapse = app.buttons["Collapse"].firstMatch
        if collapse.exists { collapse.tap(); sleep(1) }
        shot(app, "\(tag)-multiselect-collapsed")
        let caption = app.descendants(matching: .any)["attach.caption"].firstMatch
        if caption.waitForExistence(timeout: 3) {
            caption.tap()
            caption.typeText("Sunset walk 🌅")
            sleep(1)
            shot(app, "\(tag)-caption")
        }
        if sendOptions {
            let menu = app.buttons["attach.sendOptions"].firstMatch
            if menu.waitForExistence(timeout: 3) {
                menu.tap(); sleep(1)
                shot(app, "\(tag)-sendoptions")
                app.buttons["View Once"].firstMatch.tap(); sleep(1)
                shot(app, "\(tag)-viewonce")
            }
        }
    }

    func testDirectPanel() {
        let app = launch(group: false)
        walk(app, tag: "dm", sendOptions: true)
    }

    func testGroupPanel() {
        let app = launch(group: true)
        walk(app, tag: "group", sendOptions: false)
    }

    /// Picks three photos in a GROUP, captions them and sends: the group media
    /// path must put three photo bubbles in the thread, caption on the first.
    func testGroupMultiSend() {
        let app = launch(group: true)
        openPanel(app)
        guard asset(app, 0).waitForExistence(timeout: 4) else { XCTFail("no grid (photo access?)"); return }
        for i in [1, 2, 4] { asset(app, i).tap(); usleep(250_000) }
        let caption = app.descendants(matching: .any)["attach.caption"].firstMatch
        XCTAssertTrue(caption.waitForExistence(timeout: 3))
        caption.tap(); caption.typeText("Three from the weekend")
        let send = app.buttons["attach.send"].firstMatch
        XCTAssertTrue(send.exists)
        send.tap()
        XCTAssertTrue(app.descendants(matching: .any)["attach.title"].firstMatch.waitForNonExistence(timeout: 15),
                      "panel did not close after send")
        sleep(4)
        shot(app, "group-after-send")
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'Three from the weekend'"))
                        .firstMatch.waitForExistence(timeout: 5), "caption not in the thread")
    }

    func testDirectSingleSend() {
        let app = launch(group: false)
        openPanel(app)
        guard asset(app, 0).waitForExistence(timeout: 4) else { XCTFail("no grid (photo access?)"); return }
        asset(app, 1).tap()
        let caption = app.descendants(matching: .any)["attach.caption"].firstMatch
        XCTAssertTrue(caption.waitForExistence(timeout: 3))
        caption.tap(); caption.typeText("Look at this")
        app.buttons["attach.send"].firstMatch.tap()
        XCTAssertTrue(app.descendants(matching: .any)["attach.title"].firstMatch.waitForNonExistence(timeout: 15))
        sleep(4)
        shot(app, "dm-after-send")
    }

    /// Fresh permission (`simctl privacy reset photos`): the explanation card,
    /// then the real system alert → "Limit Access…" → pick two → the banner.
    func testLimitedAccessFlow() {
        let app = launch(group: true)
        openPanel(app)
        let allow = app.buttons["attach.allowAccess"].firstMatch
        guard allow.waitForExistence(timeout: 4) else { XCTFail("not in the not-determined state"); return }
        shot(app, "group-noaccess")
        allow.tap()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let limit = springboard.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Limit Access'")).firstMatch
        guard limit.waitForExistence(timeout: 8) else { XCTFail("no system alert"); return }
        limit.tap()
        sleep(3)
        shot(app, "limited-picker")
        if env["ATTACH_DUMP"] != nil { print("ATTACHDUMP\n" + app.debugDescription) }
        // The limited picker (hosted in-process): tap a few photos, then Done.
        let tiles = app.images.matching(identifier: "PXGGridLayout-Info")
        XCTAssertTrue(tiles.firstMatch.waitForExistence(timeout: 8), "no limited picker")
        for i in [0, 2, 3, 5, 6, 7] where i < tiles.count {
            tiles.element(boundBy: i).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            usleep(400_000)
        }
        let done = app.buttons["Update"].firstMatch
        if done.waitForExistence(timeout: 3) { done.tap() }
        sleep(3)
        shot(app, "group-partial")
        XCTAssertTrue(app.descendants(matching: .any)["attach.limited.manage"].firstMatch.waitForExistence(timeout: 5),
                      "no limited banner")
    }
}
