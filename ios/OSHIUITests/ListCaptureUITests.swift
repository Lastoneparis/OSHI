import XCTest

/// __LIST_ROWS_NAME_FIRST_2026_11_17__ Captures the Messages and Groups lists
/// (seeded `-OSHIScreens` data) to `$LIST_SHOT_DIR` with `$LIST_SHOT_PREFIX`,
/// and pins the row reading order: the conversation / group NAME comes first,
/// the message preview after it.
final class ListCaptureUITests: XCTestCase {

    override func setUpWithError() throws { continueAfterFailure = true }

    private var env: [String: String] { ProcessInfo.processInfo.environment }
    private var shotDir: String { env["LIST_SHOT_DIR"] ?? "/tmp/oshi-list-shots" }
    private var prefix: String { env["LIST_SHOT_PREFIX"].map { $0 + "-" } ?? "" }
    private var extraArgs: [String] {
        (env["OSHI_UI_EXTRA_ARGS"] ?? "").split(separator: " ").map(String.init)
    }

    private func shot(_ name: String) {
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try? FileManager.default.createDirectory(atPath: shotDir, withIntermediateDirectories: true)
        try? png.write(to: URL(fileURLWithPath: shotDir).appendingPathComponent(prefix + name + ".png"))
        let a = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        a.name = prefix + name; a.lifetime = .keepAlways; add(a)
    }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-OSHIScreens", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"] + extraArgs
        app.launch()
        sleep(6)
        return app
    }

    func testCaptureLists() {
        let app = launch()
        shot("messages")
        let groups = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Groups'")).firstMatch
        if groups.waitForExistence(timeout: 5) {
            groups.tap()
            sleep(3)
            shot("groups")
        } else {
            XCTFail("no Groups segment")
        }
    }

    /// Every list row reads NAME first, then the preview: the row is one
    /// accessibility element whose label follows the layout (name line above
    /// the preview). Located by its PREVIEW, so a row that leads with anything
    /// else (initials, time) fails.
    private func assertRow(_ app: XCUIApplication, name: String, previewSnippet: String,
                           file: StaticString = #filePath, line: UInt = #line) {
        let row = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", name, previewSnippet))
            .firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), "no row for \(name)", file: file, line: line)
        let label = row.label
        XCTAssertTrue(label.hasPrefix(name), "row must read the name first: \(label)", file: file, line: line)
        if let n = label.range(of: name), let p = label.range(of: previewSnippet) {
            XCTAssertLessThan(n.lowerBound, p.lowerBound, "name must precede the preview: \(label)",
                              file: file, line: line)
        }
    }

    func testRowsReadNameBeforePreview() {
        let app = launch()
        assertRow(app, name: "Alice", previewSnippet: "exactly what I")
        assertRow(app, name: "Charlie", previewSnippet: "privacy-first")
        let groups = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Groups'")).firstMatch
        XCTAssertTrue(groups.waitForExistence(timeout: 5))
        groups.tap()
        sleep(2)
        assertRow(app, name: "Book Club", previewSnippet: "Fitting choice")
        assertRow(app, name: "Family", previewSnippet: "Count me in")
    }
}
