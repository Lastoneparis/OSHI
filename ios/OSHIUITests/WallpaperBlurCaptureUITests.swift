import XCTest

/// __CHAT_CHROME_BLUR_2026_11_21__ Captures a 1:1 thread and a group thread
/// over a wallpaper, Light and Dark, to `$BLUR_SHOT_DIR`: the header and the
/// composer must be frosted panes with the wallpaper (and the bubbles passing
/// under the header) showing through, edge to edge.
/// `$BLUR_WALLPAPER` = "default" (the OSHI constellation) or "emoji".
final class WallpaperBlurCaptureUITests: XCTestCase {

    override func setUpWithError() throws { continueAfterFailure = true }

    private var env: [String: String] { ProcessInfo.processInfo.environment }
    private var shotDir: String { env["BLUR_SHOT_DIR"] ?? NSTemporaryDirectory() }

    private func shot(_ name: String) {
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try? FileManager.default.createDirectory(atPath: shotDir, withIntermediateDirectories: true)
        try? png.write(to: URL(fileURLWithPath: shotDir).appendingPathComponent(name + ".png"))
        let a = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        a.name = name; a.lifetime = .keepAlways; add(a)
    }

    private func capture(group: Bool, look: String) throws {
        // A capture, not a check: only when asked for (`BLUR_SHOT_DIR`).
        try XCTSkipUnless(env["BLUR_SHOT_DIR"] != nil, "capture only")
        let app = XCUIApplication()
        var args = ["-OSHIScreens", group ? "-OSHIGroupChat" : "-OSHIWallpaperChat",
                    "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        switch look {
        case "oled": args += ["-appTheme", "dark", "-oledBlackEnabled", "YES"]
        case "glass-light": args += ["-appTheme", "light", "-oledBlackEnabled", "NO", "-glassEffectEnabled", "YES"]
        case "glass-dark": args += ["-appTheme", "dark", "-oledBlackEnabled", "NO", "-glassEffectEnabled", "YES"]
        default: args += ["-appTheme", look, "-oledBlackEnabled", "NO", "-glassEffectEnabled", "NO"]
        }
        if env["BLUR_WALLPAPER"] == "emoji" {
            // ChatWallpaper.emojiPattern(0), JSON, as a launch-argument data literal.
            args += ["-defaultChatWallpaper", "<7b22656d6f6a695061747465726e223a7b225f30223a307d7d>"]
        }
        app.launchArguments += args
        app.launch()
        let more = app.buttons[group ? "group_overflow_menu" : "chat_overflow_menu"]
        _ = more.waitForExistence(timeout: 30)   // absent before 21/11: just wait
        sleep(4)
        let wp = env["BLUR_WALLPAPER"] ?? "default"
        shot("\(group ? "group" : "chat")-\(look)-\(wp)")
    }

    func testChatLight() throws { try capture(group: false, look: "light") }
    func testChatDark() throws { try capture(group: false, look: "dark") }
    func testGroupLight() throws { try capture(group: true, look: "light") }
    func testGroupDark() throws { try capture(group: true, look: "dark") }
    func testChatOLED() throws { try capture(group: false, look: "oled") }
    func testGroupOLED() throws { try capture(group: true, look: "oled") }
    func testChatGlassLight() throws { try capture(group: false, look: "glass-light") }
    func testGroupGlassDark() throws { try capture(group: true, look: "glass-dark") }
}
