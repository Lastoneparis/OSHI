import XCTest

/// __O_BUBBLE_MASCOT_2026_11_16__ Drives the mascot surfaces and saves
/// screenshots to `$MASCOT_SHOT_DIR` (default /tmp/oshi-mascot-shots):
/// long-press bar, "+" picker, chips under messages, the GIF picker's OSHI
/// section and a sent mascot GIF. Asserts only what must exist; the images
/// are for a human to look at.
final class MascotCaptureUITests: XCTestCase {

    override func setUpWithError() throws { continueAfterFailure = true }

    private var shotDir: String {
        ProcessInfo.processInfo.environment["MASCOT_SHOT_DIR"] ?? "/tmp/oshi-mascot-shots"
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        let png = app.screenshot().pngRepresentation
        try? FileManager.default.createDirectory(atPath: shotDir, withIntermediateDirectories: true)
        try? png.write(to: URL(fileURLWithPath: shotDir).appendingPathComponent(name + ".png"))
        let a = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        a.name = name; a.lifetime = .keepAlways; add(a)
    }

    /// Extra launch arguments from `$OSHI_UI_EXTRA_ARGS` (space-separated),
    /// e.g. "-appTheme dark -oledBlackEnabled YES" to capture under OLED black.
    static var extraArgs: [String] {
        (ProcessInfo.processInfo.environment["OSHI_UI_EXTRA_ARGS"] ?? "")
            .split(separator: " ").map(String.init)
    }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-OSHIScreens", "-OSHIOpenChat",
                                "-AppleLanguages", "(en)", "-AppleLocale", "en_US"] + Self.extraArgs
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["chat.composer"].firstMatch.waitForExistence(timeout: 25))
        sleep(2)
        return app
    }

    private func bubble(_ app: XCUIApplication, _ marker: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", marker)).firstMatch
    }

    private func react(_ app: XCUIApplication, on marker: String, with label: String,
                       viaPicker: Bool = false, shotName: String? = nil) {
        let b = bubble(app, marker)
        guard b.waitForExistence(timeout: 5) else { XCTFail("no bubble \(marker)"); return }
        b.press(forDuration: 0.8)
        let more = app.buttons["More reactions"]
        XCTAssertTrue(more.waitForExistence(timeout: 5), "reaction bar did not open")
        if let shotName {
            usleep(150_000); shot(app, shotName + "-t0.15")
            usleep(250_000); shot(app, shotName + "-t0.4")
            usleep(400_000); shot(app, shotName + "-t0.8")
        }
        if viaPicker {
            more.tap()
            sleep(1)
            if let shotName { shot(app, shotName + "-picker") }
        }
        let target = app.buttons[label].firstMatch
        XCTAssertTrue(target.waitForExistence(timeout: 5), "no \(label) button")
        target.tap()
        sleep(1)
    }

    func testCaptureReactions() {
        let app = launch()
        react(app, on: "exactly what I've been looking for", with: "Love", shotName: "1-bar")
        react(app, on: "every photo, file and voice note", with: "Fire")
        react(app, on: "Can I send photos", with: "Peeking", viaPicker: true, shotName: "2-plus")
        react(app, on: "no phone number, no email", with: "Sad")
        usleep(300_000); shot(app, "3-chips-t0.3")
        sleep(2); shot(app, "3-chips-rest")
    }

    func testCaptureMascotGif() {
        let app = launch()
        let plus = app.buttons["composer.attach"].firstMatch  // __ATTACH_PANEL_2027_2026_11_17__
        XCTAssertTrue(plus.waitForExistence(timeout: 5), "no attach button")
        plus.tap()
        let gifs = app.buttons["GIFs & Stickers"].firstMatch
        XCTAssertTrue(gifs.waitForExistence(timeout: 5), "no GIF option in the attachment panel")
        gifs.tap()
        let wave = app.descendants(matching: .any)["gif.mascot.wave"].firstMatch
        XCTAssertTrue(wave.waitForExistence(timeout: 8), "no OSHI mascot section")
        sleep(1); shot(app, "4-gif-picker")
        app.swipeUp(); sleep(1); shot(app, "4-gif-picker-scrolled")
        app.swipeDown(); sleep(1)
        let love = app.descendants(matching: .any)["gif.mascot.love"].firstMatch
        XCTAssertTrue(love.waitForExistence(timeout: 5))
        love.tap()
        sleep(4); shot(app, "5-sent-gif")
        sleep(2); shot(app, "5-sent-gif-later")
    }

    /// __GROUP_GIF_PICKER_2026_11_16__ The same picker, OSHI section first,
    /// from a GROUP composer; a mascot sent there shows as a GIF bubble.
    func testGroupGifPicker() {
        let app = XCUIApplication()
        app.launchArguments += ["-OSHIScreens", "-OSHIOpenGroup",
                                "-AppleLanguages", "(en)", "-AppleLocale", "en_US"] + Self.extraArgs
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["group.composer"].firstMatch.waitForExistence(timeout: 25),
                      "group did not open")
        sleep(2)
        let plus = app.buttons["composer.attach"].firstMatch  // __ATTACH_PANEL_2027_2026_11_17__
        XCTAssertTrue(plus.waitForExistence(timeout: 5), "no attach button in the group composer")
        plus.tap()
        sleep(1); shot(app, "7-group-plus-panel")
        XCTAssertTrue(app.buttons["Share Contact"].firstMatch.exists
                      || app.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'contact'")).firstMatch.exists,
                      "no contact option in the group panel")
        let gifs = app.buttons["GIFs & Stickers"].firstMatch
        XCTAssertTrue(gifs.waitForExistence(timeout: 5), "no GIF option in the group panel")
        gifs.tap()
        let love = app.descendants(matching: .any)["gif.mascot.love"].firstMatch
        XCTAssertTrue(love.waitForExistence(timeout: 8), "no OSHI mascot section in the group picker")
        sleep(1); shot(app, "7-group-gif-picker")
        love.tap()
        sleep(4); shot(app, "7-group-sent-gif")
    }

    func testCaptureSettingOff() {
        let app = XCUIApplication()
        app.launchArguments += ["-OSHIScreens", "-OSHIOpenChat", "-animatedOSHIReactions", "NO",
                                "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["chat.composer"].firstMatch.waitForExistence(timeout: 25))
        sleep(2)
        react(app, on: "exactly what I've been looking for", with: "Love", shotName: "6-setting-off-bar")
        sleep(1); shot(app, "6-setting-off-chips")
    }

    /// Plain launch that answers the first-run system alerts, so a screen
    /// recording of the NEXT cold launch shows the launch animation unobstructed.
    func testPrepareLaunchRecording() {
        let app = XCUIApplication()
        app.launch()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for _ in 0..<4 {
            let allow = springboard.buttons["Allow"].firstMatch
            if allow.waitForExistence(timeout: 4) { allow.tap() } else { break }
        }
        sleep(2)
        shot(app, "0-after-first-launch")
    }
}
