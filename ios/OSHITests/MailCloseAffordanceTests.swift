//
//  MailCloseAffordanceTests.swift
//  OSHITests
//
//  __MAIL_CLOSE_WAS_A_DEAD_BUTTON_2026_11_06__ Owner report: "into email view
//  there is a cross on ios on the top left corner close to contact/boite icone
//  that seems to be useless".
//
//  It was useless, and this is the second time the same button has been
//  reported. First it was `Button("common.done")` — "OK" in French — and the
//  report was "the OK button, which I think does nothing". On 03/11 it was
//  re-labelled an xmark instead of removed, on the reasoning that MailView was
//  presented as a `fullScreenCover` and this was the only way out.
//
//  That reasoning had already expired. Email had become its own segment of
//  ChatsTabView, which is a root tab of the TabView, so nothing presents
//  MailView and `@Environment(\.dismiss)` had nothing to dismiss. The one
//  remaining `fullScreenCover(isPresented: $showMail)` in MessagesListView was
//  itself unreachable: no code ever set `showMail`. So the xmark was dead on
//  both counts — relabelling it made the word right and left the behaviour
//  broken.
//
//  What this file pins is the INVARIANT, not the absence of a button: a
//  MailView that is presented modally must have a way out, and a MailView that
//  is embedded must not pretend to offer one. Either half flipping is a bug.
//

import XCTest

final class MailCloseAffordanceTests: XCTestCase {

    // MARK: - Where MailView actually lives

    func testMailViewIsEmbeddedAsATabSegmentNotPresented() throws {
        let tabs = try Self.repoFile("OSHI/ChatsTabView.swift")
        XCTAssertTrue(tabs.contains("MailView(deepLinkMessageId: $pendingMailMessageId)"),
                      "the Email segment is the live route into the mailbox")

        // It must be a plain child of the segment switch, not something the
        // segment presents — a `sheet`/`fullScreenCover` here would reintroduce
        // the need for a close affordance.
        for presentation in ["sheet", "fullScreenCover", "popover"] {
            XCTAssertFalse(
                tabs.contains("\(presentation) {\n                MailView"),
                "ChatsTabView presents MailView via .\(presentation) — then it needs a documented way out")
        }
    }

    func testNoUnreachableModalPresentationOfMailViewSurvives() throws {
        let list = try Self.repoFile("OSHI/MessagesListView.swift")
        XCTAssertFalse(list.contains("fullScreenCover(isPresented: $showMail)"),
                       "the dead cover is back; nothing ever sets showMail, so it is a view no user can reach")
        XCTAssertFalse(list.contains("@State private var showMail"),
                       "state with no writer is the shape this bug hid in for three days")
    }

    // MARK: - The invariant

    func testEveryModalPresentationOfMailViewCarriesAWayOut() throws {
        let mail = try Self.repoFile("OSHI/MailView.swift")
        let presenters = ["OSHI/ChatsTabView.swift", "OSHI/MessagesListView.swift", "OSHI/ContentView.swift"]

        var presentedModally = false
        for path in presenters {
            let source = try Self.repoFile(path)
            for presentation in ["sheet", "fullScreenCover", "popover"] {
                // Look only at presentation modifiers whose body mentions MailView.
                var searchFrom = source.startIndex
                while let hit = source.range(of: ".\(presentation)(", range: searchFrom..<source.endIndex) {
                    let window = source[hit.upperBound...].prefix(400)
                    // `MailComposeView` / `MailAccountView` are MailView's OWN sheets and
                    // have their own Cancel/Done — only the mailbox root matters here.
                    if window.contains("MailView()") || window.contains("MailView(deepLink") {
                        presentedModally = true
                    }
                    searchFrom = hit.upperBound
                }
            }
        }

        if presentedModally {
            XCTAssertTrue(
                mail.contains("xmark") || mail.contains("common.close") || mail.contains("\\.dismiss"),
                "MailView is presented modally again but offers no close affordance — a fullScreenCover has no back chevron, so the user is trapped in the mailbox")
        } else {
            // The current state: embedded only, so a dismiss-based close button
            // would be a button that silently does nothing.
            //
            // Scoped to MailView's OWN property block. The first version of this
            // guard matched the whole file and tripped on MailMessageView, which
            // declares the same pair of properties and legitimately needs
            // `dismiss` — it is pushed, so dismissing pops the push.
            let properties = try Self.slice(mail, from: "struct MailView: View {", upTo: "var body: some View {")
            XCTAssertFalse(
                properties.contains("\\.dismiss"),
                "MailView holds a dismiss it cannot use; that is what made the cross dead")
            XCTAssertTrue(properties.contains("@StateObject private var mail = OSHIMailClient.shared"),
                          "sanity: this slice really is MailView's property block")
        }
    }

    func testMailViewLeadingToolbarKeepsOnlyTheAccountButton() throws {
        let mail = try Self.repoFile("OSHI/MailView.swift")
        let toolbar = try Self.slice(mail, from: ".toolbar {", upTo: ".sheet(isPresented: $showCompose)")

        XCTAssertTrue(toolbar.contains("person.crop.circle"),
                      "the account button is the icon the owner located the cross next to; it stays")
        XCTAssertFalse(toolbar.contains("xmark"),
                       "the cross is back in the mailbox toolbar; it has nothing to dismiss")
        XCTAssertFalse(toolbar.contains("common.done"),
                       "`common.done` is \"OK\" in French — the first shape of this same bug")
        XCTAssertTrue(toolbar.contains("square.and.pencil"), "Compose must survive the removal")
    }

    // MARK: - The other platforms, checked rather than assumed

    func testAndroidMailboxHasAWayOutAndItIsWiredToARealPop() throws {
        // This assertion was written BACKWARDS on the first pass: it asserted
        // Android's mailbox top bar had NO navigationIcon, "nothing to go dead".
        // Half an hour later the Android half of the same fix ADDED one, because
        // the bottom bar is hidden on that route and the mailbox had no
        // on-screen exit at all — and this test went red against the fix it was
        // supposed to protect. The failure message was already the right rule
        // ("if Android grows a navigationIcon it must pop the back stack, not
        // no-op"); the assertion just said the opposite of it.
        //
        // Pinning an absence was the mistake. The invariant is symmetric with
        // iOS and holds on both: a mailbox you cannot leave is broken, and so is
        // a button that claims to leave it and doesn't.
        let screen = try Self.repoFile("OSHI-Android/app/src/main/java/com/oshi/messenger/ui/screens/MailScreen.kt")
        let topBarEnd = screen.range(of: "TabRow(selectedTabIndex = tab)")
        let topBar = String(screen[screen.startIndex..<(topBarEnd?.lowerBound ?? screen.endIndex)])
        XCTAssertTrue(topBar.contains("Icons.Default.Refresh"), "sanity: this is the mailbox top bar")

        XCTAssertTrue(topBar.contains("navigationIcon"),
                      "the Android mailbox has no on-screen way out; the bottom bar is hidden on this route")
        XCTAssertTrue(topBar.contains("IconButton(onClick = onBack)"),
                      "the navigation icon is not wired to a real action — that is the iOS bug, ported")
        XCTAssertFalse(screen.contains("onBack: () -> Unit = {}"),
                       "a no-op default turns the back arrow into the dead cross this file is about")
        // The behavioural detail (that `onBack` is `navController.popBackStack()`)
        // is pinned on the Android side, where the call site lives:
        // `MailboxWayOutTest.onBack_is_required_and_really_pops_the_back_stack`.
    }

    func testDesktopMailboxIsAPaneWithNoCloseButton() throws {
        let pane = try Self.repoFile("OSHI-Desktop/src/main/kotlin/com/oshi/desktop/ui/components/MailPane.kt")
        XCTAssertFalse(pane.contains("Icons.Default.Close"),
                       "the desktop mailbox is a pane in the main window; a close button there would have nothing to close")
    }

    // MARK: - The stripper, before anything is trusted to it

    func testCommentStripperKeepsCodeAndDropsProse() {
        let source = """
        // a cross: xmark.circle.fill, Button("common.done")
        Button { showCompose = true }  // trailing xmark note
        let site = "https://oshi.im/mail"
        """
        let stripped = Self.strippingComments(source)
        XCTAssertFalse(stripped.contains("xmark"), "prose survived: \(stripped)")
        XCTAssertFalse(stripped.contains("common.done"))
        XCTAssertTrue(stripped.contains("Button { showCompose = true }"), "code was eaten: \(stripped)")
        XCTAssertTrue(stripped.contains("https://oshi.im/mail"), "a URL is not a comment")
        XCTAssertEqual(stripped.split(separator: "\n", omittingEmptySubsequences: false).count, 3,
                       "line numbering must survive — a stripped line stays, empty")
    }

    // MARK: - Helpers

    /// The first run of this file failed three of its own assertions: the
    /// comments explaining the bug quote the very strings the guards look for
    /// (`xmark`, `common.done`, `fullScreenCover(isPresented: $showMail)`). A
    /// guard that trips on its own prose protects nothing, so every read here
    /// goes through this: `//` line comments stripped, code kept. `://` is left
    /// alone so URLs survive.
    static func strippingComments(_ source: String) -> String {
        source.split(separator: "\n", omittingEmptySubsequences: false).map { line -> String in
            var out = ""
            var previous: Character?
            var index = line.startIndex
            while index < line.endIndex {
                let character = line[index]
                let next = line.index(after: index)
                if character == "/", next < line.endIndex, line[next] == "/", previous != ":" {
                    break
                }
                out.append(character)
                previous = character
                index = next
            }
            return out
        }.joined(separator: "\n")
    }

    private static func repoFile(_ relative: String) throws -> String {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent(relative)
            if FileManager.default.fileExists(atPath: candidate.path) {
                return strippingComments(try String(contentsOf: candidate, encoding: .utf8))
            }
            dir = dir.deletingLastPathComponent()
        }
        throw XCTSkip("\(relative) not found from \(#filePath)")
    }

    private static func slice(_ source: String, from start: String, upTo end: String) throws -> String {
        guard let a = source.range(of: start), let b = source.range(of: end, range: a.upperBound..<source.endIndex)
        else { throw XCTSkip("could not bracket \(start) … \(end)") }
        return String(source[a.upperBound..<b.lowerBound])
    }
}
