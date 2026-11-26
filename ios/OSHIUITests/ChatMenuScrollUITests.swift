import XCTest

/// __MENU_SCROLL_RESET_2026_11_21__ « sur les 3 points dans une discussion iOS
/// le menu lorsqu'on baisse la liste semble remonter tout seul après quelques
/// secondes ». The conversation's "…" menu is long enough to scroll; while it
/// was open, any republish of the thread's observed objects rebuilt the menu's
/// content and UIKit, handed a new UIMenu, reset the list to the top.
///
/// The test scrolls the open menu, waits longer than the periodic refreshes
/// that used to reset it, and checks the first item has not come back.
/// Also captures before/after to `$MENU_SHOT_DIR` when set.
final class ChatMenuScrollUITests: XCTestCase {

    override func setUpWithError() throws { continueAfterFailure = false }

    private var shotDir: String? { ProcessInfo.processInfo.environment["MENU_SHOT_DIR"] }

    private func shot(_ name: String) {
        let png = XCUIScreen.main.screenshot().pngRepresentation
        let a = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        a.name = name; a.lifetime = .keepAlways; add(a)
        guard let dir = shotDir else { return }
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        try? png.write(to: URL(fileURLWithPath: dir).appendingPathComponent(name + ".png"))
    }

    private func openMenu(group: Bool) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-OSHIScreens", group ? "-OSHIGroupChat" : "-OSHIWallpaperChat",
                                "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
                                "-appTheme", "light",
                                // Large text: the menu must scroll on every device.
                                "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityL"]
        app.launch()
        let more = app.buttons[group ? "group_overflow_menu" : "chat_overflow_menu"]
        let found = more.waitForExistence(timeout: 40)
        if !found { shot(group ? "group-no-button" : "chat-no-button") }
        XCTAssertTrue(found, "the … button")
        sleep(3)
        more.tap()
        return app
    }

    /// The topmost row the menu is showing, and where it is.
    private func topRow(in list: XCUIElement) -> (String, CGFloat)? {
        // First row fully below the menu's top edge (a row half scrolled out
        // still reports as hittable).
        let top = list.frame.minY
        let rows = list.buttons.allElementsBoundByIndex.filter { $0.exists && $0.frame.minY >= top - 1 }
        guard let row = rows.min(by: { $0.frame.minY < $1.frame.minY }) else { return nil }
        return (row.label, row.frame.minY)
    }

    /// Scrolls the open menu, waits, and checks it is still showing the same
    /// rows at the same place (the reset put it back on its first row).
    private func checkMenuKeepsItsScroll(group: Bool, firstLabel: String) {
        let app = openMenu(group: group)
        let first = app.buttons[firstLabel]
        let opened = first.waitForExistence(timeout: 10)
        shot(group ? "group-menu-open" : "chat-menu-open")
        XCTAssertTrue(opened, "\(firstLabel) in the open menu")
        sleep(1)
        // The menu's own list: the last collection view, above the thread's.
        guard let list = app.collectionViews.allElementsBoundByIndex.last(where: {
            $0.buttons[firstLabel].exists
        }) else { return XCTFail("the menu's list") }
        XCTAssertEqual(topRow(in: list)?.0, firstLabel)
        list.swipeUp(velocity: .slow)
        sleep(1)
        guard let scrolled = topRow(in: list) else { return XCTFail("menu rows") }
        shot(group ? "group-menu-scrolled" : "chat-menu-scrolled")
        XCTAssertNotEqual(scrolled.0, firstLabel, "the menu scrolled")
        // Unfixed, the list was swapped for a new one at offset 0 a second or
        // two after the swipe ("after a few seconds" in the report).
        sleep(10)
        shot(group ? "group-menu-after-10s" : "chat-menu-after-10s")
        guard let later = topRow(in: list) else { return XCTFail("the menu closed") }
        XCTAssertEqual(later.0, scrolled.0, "the open menu scrolled back by itself")
        XCTAssertEqual(later.1, scrolled.1, accuracy: 2, "the open menu moved by itself")
    }

    func testConversationMenuKeepsItsScroll() {
        checkMenuKeepsItsScroll(group: false, firstLabel: "Schedule call")
    }

    func testGroupMenuKeepsItsScroll() {
        checkMenuKeepsItsScroll(group: true, firstLabel: "Group Info")
    }
}
