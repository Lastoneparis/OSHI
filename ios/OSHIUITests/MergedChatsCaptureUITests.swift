import XCTest

/// __MERGED_CHATS_2026_11_18__ The merged Messages + Groups list, on seeded
/// `-OSHIScreens` data: captures (to `$MERGED_SHOT_DIR`, prefix
/// `$MERGED_SHOT_PREFIX`) and the behaviour that must hold — option ON shows one
/// "Chats" segment with people AND groups, the chips filter, the "New…" sheet
/// carries the group actions; option OFF keeps the three segments.
final class MergedChatsCaptureUITests: XCTestCase {

    override func setUpWithError() throws { continueAfterFailure = true }

    private var env: [String: String] { ProcessInfo.processInfo.environment }
    private var shotDir: String { env["MERGED_SHOT_DIR"] ?? "/tmp/oshi-merged-shots" }
    private var prefix: String { env["MERGED_SHOT_PREFIX"].map { $0 + "-" } ?? "" }
    /// "light" (default), "dark" or "oled".
    private var look: String { env["MERGED_SHOT_LOOK"] ?? "light" }

    private func shot(_ name: String) {
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try? FileManager.default.createDirectory(atPath: shotDir, withIntermediateDirectories: true)
        try? png.write(to: URL(fileURLWithPath: shotDir).appendingPathComponent(prefix + name + ".png"))
        let a = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        a.name = prefix + name; a.lifetime = .keepAlways; add(a)
    }

    private func launch(merged: Bool, _ extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        var args = ["-OSHIScreens", "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
                    "-chatsMergedEnabled", merged ? "YES" : "NO"]
        switch look {
        case "dark": args += ["-appTheme", "dark", "-oledBlackEnabled", "NO"]
        case "oled": args += ["-appTheme", "dark", "-oledBlackEnabled", "YES"]
        default: args += ["-appTheme", "light"]
        }
        app.launchArguments += args + extra
        app.launch()
        sleep(12)
        return app
    }

    func testMergedListShowsPeopleAndGroupsWithChips() {
        let app = launch(merged: true)
        XCTAssertTrue(app.buttons["segment_chats"].waitForExistence(timeout: 30), "one Chats segment")
        XCTAssertFalse(app.buttons["segment_groups"].exists, "no Groups segment when merged")
        XCTAssertTrue(app.buttons["segment_email"].exists, "Email stays")
        XCTAssertTrue(app.descendants(matching: .any)["merged_filters"].waitForExistence(timeout: 5))
        // People and groups in the same list (seeded: Alice … and "Family").
        let alice = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH 'Alice'")).firstMatch
        let club = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH 'Family'")).firstMatch
        XCTAssertTrue(alice.waitForExistence(timeout: 30))
        XCTAssertTrue(club.exists, "groups are in the merged list")
        shot("list")

        app.buttons["merged_filter_groups"].tap()
        sleep(2)
        XCTAssertTrue(club.exists)
        XCTAssertFalse(alice.exists, "Groups chip hides people")
        shot("filter-groups")

        // __MERGED_GROUP_TYPES_2026_11_18__ the classic Groups segment's type chips.
        XCTAssertTrue(app.descendants(matching: .any)["merged_group_types"].waitForExistence(timeout: 5),
                      "type sub-filter under the Groups chip")
        for id in ["merged_group_type_all", "merged_group_type_admin_only",
                   "merged_group_type_collaborative", "merged_group_type_public"] {
            XCTAssertTrue(app.buttons[id].exists, id)
        }
        app.buttons["merged_group_type_public"].tap()
        sleep(2)
        XCTAssertFalse(alice.exists)
        shot("filter-groups-public")
        app.buttons["merged_group_type_all"].tap()
        sleep(1)

        app.buttons["merged_filter_people"].tap()
        sleep(2)
        XCTAssertTrue(alice.exists)
        XCTAssertFalse(club.exists, "People chip hides groups")
        XCTAssertFalse(app.descendants(matching: .any)["merged_group_types"].exists, "sub-filter only under Groups")
        app.buttons["merged_filter_all"].tap()
        sleep(1)

        app.buttons["compose_new"].tap()
        XCTAssertTrue(app.buttons["merged_new_group"].waitForExistence(timeout: 5), "New group in the New… sheet")
        XCTAssertTrue(app.buttons["merged_new_discover"].exists, "Discover in the New… sheet")
        XCTAssertTrue(app.buttons["merged_new_chat"].exists)
        XCTAssertTrue(app.buttons["merged_new_scanInvite"].exists)
        sleep(1)
        shot("new-sheet")

        app.buttons["merged_new_discover"].tap()
        sleep(3)
        shot("discover")
    }

    func testOptionOffKeepsTheThreeSegments() {
        let app = launch(merged: false)
        XCTAssertTrue(app.buttons["segment_messages"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.buttons["segment_groups"].exists)
        XCTAssertTrue(app.buttons["segment_email"].exists)
        XCTAssertFalse(app.buttons["segment_chats"].exists)
        XCTAssertFalse(app.descendants(matching: .any)["merged_filters"].exists)
        shot("off")
    }

    func testFirstRunShowsTheDiscoverCard() {
        let app = launch(merged: true, ["-OSHIEmpty"])
        XCTAssertTrue(app.descendants(matching: .any)["merged_discover_teaser"].waitForExistence(timeout: 30))
        shot("empty-discover")
    }

    func testWideLayoutOpensAGroupInTheDetailPane() throws {
        let app = launch(merged: true)
        let club = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH 'Family'")).firstMatch
        XCTAssertTrue(club.waitForExistence(timeout: 30))
        club.tap()
        sleep(4)
        if app.descendants(matching: .any)["thread.detail"].exists {
            // Wide: the list is still on screen beside the thread.
            XCTAssertTrue(app.descendants(matching: .any)["merged_filters"].exists, "list stays in the sidebar")
        }
        shot("open-group")
    }

    func testAppearanceSettingCarriesTheToggle() {
        let app = launch(merged: true, ["-OSHIAppearanceSettings"])
        let toggle = app.switches["settings.appearance.mergedChats"]
        if !toggle.waitForExistence(timeout: 8) { app.swipeUp() }
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        shot("settings")
    }
}
