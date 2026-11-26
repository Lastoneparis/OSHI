import XCTest

/// Automated App Store screenshot capture for OSHI.
/// Loads demo content via the proven APPLE-REVIEW-2026 recovery-key path (the
/// same one App Store reviewers use), dismisses the system notification alert,
/// then captures each primary screen as a named XCTAttachment. Run per-locale by
/// setting the simulator language; the captures are extracted from the .xcresult.
final class OSHIScreenshotTests: XCTestCase {

    override func setUpWithError() throws {
        // Keep capturing even if one tap is flaky — a single missed tab must not
        // abort the whole screenshot run.
        continueAfterFailure = true
    }

    func testCaptureScreens() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-OSHIScreens"]
        // Force the app UI language for this capture run. Override per-locale by
        // changing these two args (e.g. "(fr)" / "fr_FR").
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]

        // Auto-dismiss the system notification (and any other) permission alert.
        addUIInterruptionMonitor(withDescription: "Permission alert") { alert in
            for label in ["Allow", "Don't Allow", "Allow Once", "OK", "Continue",
                          "Autoriser", "Erlauben", "Permitir", "Consenti", "許可"] {
                let b = alert.buttons[label]
                if b.exists { b.tap(); return true }
            }
            // Fallback: tap the last button (usually the affirmative/dismiss).
            if alert.buttons.count > 0 { alert.buttons.element(boundBy: alert.buttons.count - 1).tap(); return true }
            return false
        }

        app.launch()

        // `-OSHIScreens` already skips onboarding, forces a demo identity, and
        // seeds demo conversations/groups (SecureWeb3MessengerApp + ContentView).
        // So there is NO import flow to drive — just wait for the tab UI + the
        // demo seed (loaded ~0.6s after ContentView appears) to settle.
        let tabBarWait = app.tabBars.firstMatch
        XCTAssertTrue(tabBarWait.waitForExistence(timeout: 30), "main tab bar not found")
        app.tap()  // nudge the interruption monitor for the notification alert
        Thread.sleep(forTimeInterval: 6)

        // --- Capture each primary tab ---
        // Tabs: 0 Messages · 1 AI · 2 New · 3 Map · 4 More
        let names = ["01_messages", "02_ai", "03_new", "04_map", "05_more"]
        let tabBar = app.tabBars.firstMatch
        for (i, name) in names.enumerated() {
            tapTab(tabBar, i)
            capture(app, name)
        }

        // Demo mode opens directly INTO a 1:1 conversation, so tab 0 ("Messages")
        // already IS the real chat screen (captured as 01_messages). From there:

        // --- Chat LIST: leave the open conversation via the nav back button and
        // capture the real conversation list. ---
        tapTab(tabBar, 0)
        let back = app.navigationBars.buttons.element(boundBy: 0)
        if back.waitForExistence(timeout: 4), back.isHittable {
            back.tap()
            Thread.sleep(forTimeInterval: 1.5)
            capture(app, "06_chatlist")
        }

        // --- Re-open the first 1:1 via its STABLE identifier (deterministic, no
        // index guessing) to guarantee a clean chat capture. ---
        if tapIfExists(app.buttons["conversation_row_first"], timeout: 5)
            || tapIfExists(app.otherElements["conversation_row_first"], timeout: 1) {
            Thread.sleep(forTimeInterval: 3.0)   // decrypt + settle to bottom
            capture(app, "07_conversation")
            let b = app.navigationBars.buttons.element(boundBy: 0)
            if b.exists, b.isHittable { b.tap(); Thread.sleep(forTimeInterval: 1.2) }
        }

        // --- GROUPS: switch to the Groups segment via its stable identifier,
        // capture the list, then open the first group by its stable identifier. ---
        if tapIfExists(app.buttons["segment_groups"], timeout: 5) {
            Thread.sleep(forTimeInterval: 1.5)
            capture(app, "08_groups")
            if tapIfExists(app.buttons["group_row_first"], timeout: 5)
                || tapIfExists(app.otherElements["group_row_first"], timeout: 1) {
                Thread.sleep(forTimeInterval: 3.0)
                capture(app, "09_group_chat")
            }
        }
    }

    /// Tap a tab bar button by index, waiting for it to become hittable.
    private func tapTab(_ tabBar: XCUIElement, _ index: Int) {
        let btn = tabBar.buttons.element(boundBy: index)
        if btn.waitForExistence(timeout: 5), btn.isHittable {
            btn.tap()
            Thread.sleep(forTimeInterval: 1.8)
        }
    }

    /// Tap an element once it exists + is hittable; returns whether it tapped.
    @discardableResult
    private func tapIfExists(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        guard element.waitForExistence(timeout: timeout), element.isHittable else { return false }
        element.tap()
        return true
    }

    /// __REPLY_LOOK_2026_08_19__ Captures the reply surfaces, because five
    /// redesigns were judged from a diff instead of from a screen — and three of
    /// them were redesigning a view the app never renders.
    ///
    /// `09_chat_bubbles` / `10_reply_composer` / `11_reply_in_bubble`.
    /// The last one is the surface that kept looking like a rectangle.
    func testCaptureReplySurfaces() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-OSHIScreens"]
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        addUIInterruptionMonitor(withDescription: "Permission alert") { alert in
            if alert.buttons.count > 0 {
                alert.buttons.element(boundBy: alert.buttons.count - 1).tap(); return true
            }
            return false
        }
        app.launch()

        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 30), "no tab bar")
        app.tap()
        Thread.sleep(forTimeInterval: 6)

        // Open the first seeded conversation.
        //
        // ⚠️ NOT `app.cells`: this list is SwiftUI and exposes no Cell elements at
        // all — the first attempt failed with "No matches found for Descendants
        // matching type Cell". Try the real element types, then fall back to a
        // coordinate, because a capture test that cannot open a chat proves
        // nothing and a selector guess is not worth failing over.
        let window = app.windows.firstMatch
        var opened = false
        for candidate in [app.buttons.element(boundBy: 0), app.cells.element(boundBy: 0)]
        where !opened {
            if candidate.waitForExistence(timeout: 4), candidate.isHittable {
                candidate.tap(); opened = true
            }
        }
        if !opened {
            // First row sits at ~0.39 of the window height on this seed.
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.45, dy: 0.39)).tap()
        }
        Thread.sleep(forTimeInterval: 4)
        capture(app, "09_chat_bubbles")

        // Swipe a bubble right to reply. Start well inside the message area: an
        // edge drag is the interactive back gesture and would leave the chat.
        //
        // ⚠️ The first run swiped at dy 0.45 and captured a composer with NO reply
        // strip: that band is empty space above the first bubble. A capture test
        // that misses its target still passes and still produces a screenshot —
        // which is exactly how a redesign gets "verified" against nothing. So try
        // the bands where bubbles actually are, and STOP at the first one that
        // makes the strip appear.
        var replyArmed = false
        for dy in [0.52, 0.64, 0.76] where !replyArmed {
            let start = window.coordinate(withNormalizedOffset: CGVector(dx: 0.30, dy: dy))
            let end = window.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: dy))
            start.press(forDuration: 0.08, thenDragTo: end)
            Thread.sleep(forTimeInterval: 2)
            // The strip carries the only Cancel button on this screen.
            replyArmed = app.buttons["Cancel"].exists || app.buttons["Annuler"].exists
        }
        XCTAssertTrue(replyArmed,
                      "swipe-to-reply never armed — the composer capture would be meaningless")
        Thread.sleep(forTimeInterval: 1)
        capture(app, "10_reply_composer")

        // Send it, so the quote renders INSIDE a bubble — the surface three
        // redesigns never actually touched.
        let field = app.textViews.firstMatch.exists
            ? app.textViews.firstMatch
            : app.textFields.firstMatch
        if field.waitForExistence(timeout: 8) {
            field.tap()
            field.typeText("quote check")
            if app.keyboards.buttons["Return"].exists {
                app.keyboards.buttons["Return"].tap()
            }
            Thread.sleep(forTimeInterval: 4)
        }
        capture(app, "11_reply_in_bubble")

        XCTAssertTrue(app.exists, "app died during the reply capture")
    }

    private func capture(_ app: XCUIApplication, _ name: String) {
        let shot = app.screenshot()
        let att = XCTAttachment(screenshot: shot)
        att.name = name
        att.lifetime = .keepAlways
        add(att)
    }
}

// MARK: - Two-simulator call matrix
//
// Drives a REAL call between two simulators. One sim runs a caller test, the
// other runs `testCalleeAnswerMachine`, which passively accepts whatever comes
// in. The UI test only *drives* the scenario — the verdict comes from parsing
// the DIAG_* log lines both apps emit (see scratchpad/call_matrix.sh +
// parse_call_logs.js), because a simulator cannot prove audio acoustically.
//
// Parameters arrive as environment variables. `xcodebuild test` forwards any
// var prefixed `TEST_RUNNER_` to the runner process with the prefix stripped:
//   TEST_RUNNER_OSHI_PEER_KEY=<peer public key>   (required for caller roles)
//   TEST_RUNNER_OSHI_CALL_SECONDS=<n>             (default 20)
//   TEST_RUNNER_OSHI_CALL_MATRIX=1                 (two-runner matrix enabled)
//
// CallKit is bypassed via `-callKitDisabled YES`: PushKit/CallKit do not work in
// the Simulator, so ringing must go through the in-app WebSocket signal path.
// The app already exposes that as a UserDefaults key, and NSArgumentDomain turns
// the launch arg into that key — so no app code change is needed.
//
// Labels are matched in English and the UI language is pinned with
// `-AppleLanguages "(en)"`, because none of the call controls carry an
// accessibilityIdentifier and the app ships 34 localisations.
final class CallMatrixUITests: XCTestCase {

    private var peerKey: String {
        ProcessInfo.processInfo.environment["OSHI_PEER_KEY"] ?? ""
    }
    private var callSeconds: TimeInterval {
        TimeInterval(ProcessInfo.processInfo.environment["OSHI_CALL_SECONDS"] ?? "") ?? 20
    }
    private var callMatrixEnabled: Bool {
        ProcessInfo.processInfo.environment["OSHI_CALL_MATRIX"] == "1"
    }

    /// These scenarios have no meaningful one-simulator result: callers need a
    /// concurrently running callee, and the callee intentionally waits for one.
    /// A normal `xcodebuild test` therefore skips them rather than timing out;
    /// the paired matrix script opts in explicitly.
    private func requireCallMatrix() throws {
        try XCTSkipUnless(callMatrixEnabled,
                          "requires two concurrent runners; set TEST_RUNNER_OSHI_CALL_MATRIX=1")
    }

    override func setUpWithError() throws {
        continueAfterFailure = true
    }

    // MARK: Scenarios

    /// CALLEE. Accepts incoming calls for the whole window, as many times as they
    /// arrive — one run therefore covers the basic call, the fast hangup, and the
    /// re-call that follows it.
    func testCalleeAnswerMachine() throws {
        try requireCallMatrix()
        let app = launchApp()
        settle(app)
        capture(app, "callee_ready")

        var accepted = 0
        let deadline = Date().addingTimeInterval(240)
        while Date() < deadline {
            guard let accept = waitForAcceptButton(app, timeout: 8) else { continue }
            accept.tap()
            accepted += 1
            NSLog("CALLMATRIX callee accepted call #\(accepted)")
            capture(app, "callee_accepted_\(accepted)")

            // Stay in the call until the peer tears it down (the caller owns the
            // hangup in every scenario), then loop to catch a re-call.
            let endBtn = app.buttons["End call"]
            let callDeadline = Date().addingTimeInterval(callSeconds + 30)
            while endBtn.exists && Date() < callDeadline { Thread.sleep(forTimeInterval: 1) }
            NSLog("CALLMATRIX callee call #\(accepted) ended")
            Thread.sleep(forTimeInterval: 2)
        }
        NSLog("CALLMATRIX callee total accepted=\(accepted)")
        XCTAssertGreaterThan(accepted, 0, "callee never saw an incoming call")
    }

    /// CALLER S1 — place a call, hold it, hang up cleanly.
    /// This is the scenario whose logs prove audio actually flows.
    func testCallerBasic() throws {
        try requireCallMatrix()
        let app = launchApp()
        settle(app)
        try openPeerChat(app)
        // Give the callee runner time to reach its accept loop.
        Thread.sleep(forTimeInterval: 25)

        NSLog("CALLMATRIX caller placing call")
        try startVoiceCall(app)
        capture(app, "caller_ringing")

        let endBtn = app.buttons["End call"]
        XCTAssertTrue(endBtn.waitForExistence(timeout: 45), "call UI never appeared")
        Thread.sleep(forTimeInterval: callSeconds)   // hold — DIAG_SNAP samples here
        capture(app, "caller_in_call")

        NSLog("CALLMATRIX caller hanging up")
        if endBtn.exists, endBtn.isHittable { endBtn.tap() }
        Thread.sleep(forTimeInterval: 5)
    }

    /// CALLER S2+S3 — place a call, hang up, then call again. The FIRST call is the
    /// one that yields the audio verdict (its DIAG_* land in the log regardless of
    /// the second call), so this test's value does not hinge on the re-call.
    ///
    /// The re-call is BEST-EFFORT and here's why: MEASURED across four runs, a
    /// hangup dismisses VoiceCallView and pops the nav stack back to the
    /// conversation LIST, and during that teardown the call button flickers in and
    /// out of the hierarchy — so the automation cannot reliably re-tap it for
    /// 15-20 s. That is a simulator UI-driving limitation, not an app defect, so a
    /// failure to re-drive must NOT hard-fail the scenario and masquerade as a bug.
    /// Strict "re-call while the previous call is still tearing down" reentry needs
    /// two real devices.
    func testCallerFastHangupAndRecall() throws {
        try requireCallMatrix()
        let app = launchApp()
        settle(app)
        try openPeerChat(app)
        Thread.sleep(forTimeInterval: 25)

        NSLog("CALLMATRIX caller fast-hangup call")
        try startVoiceCall(app)
        Thread.sleep(forTimeInterval: 4)             // let it connect, then hang up
        let endBtn = app.buttons["End call"]
        if endBtn.waitForExistence(timeout: 5), endBtn.isHittable { endBtn.tap() }
        capture(app, "caller_fast_hangup")

        // Best-effort re-call. Let teardown fully settle, re-open the chat, retry.
        Thread.sleep(forTimeInterval: 4)
        NSLog("CALLMATRIX caller re-calling after hangup")
        do {
            try openPeerChat(app)
            try startVoiceCall(app)
            if endBtn.waitForExistence(timeout: 45) {
                NSLog("CALLMATRIX recall second call ESTABLISHED")
                Thread.sleep(forTimeInterval: callSeconds)
                capture(app, "caller_recall_in_call")
                if endBtn.exists, endBtn.isHittable { endBtn.tap() }
                Thread.sleep(forTimeInterval: 5)
            }
        } catch {
            NSLog("CALLMATRIX recall second call UNDRIVABLE in sim (teardown UI) — first-call verdict still stands")
            capture(app, "caller_recall_undrivable")
        }
    }

    /// GLARE — run this SAME method on BOTH sims. Both place a call at once, so
    /// exactly one side must win the alphabetical pubkey tie-break
    /// (PHONE_TAG_COLLISION) and both must converge on a single callId.
    func testGlare() throws {
        try requireCallMatrix()
        let app = launchApp()
        settle(app)
        try openPeerChat(app)

        // Wall-clock barrier: both sims dial at the SAME absolute instant. A fixed
        // sleep drifts because per-sim setup time differs, and without overlapping
        // ring windows the glare tie-break (PHONE_TAG_COLLISION) never fires — the
        // first run showed both placing calls but zero collision. The script passes
        // the same epoch to both runners.
        if let s = ProcessInfo.processInfo.environment["OSHI_DIAL_AT"], let dialAt = Double(s) {
            while Date().timeIntervalSince1970 < dialAt { Thread.sleep(forTimeInterval: 0.05) }
        } else {
            Thread.sleep(forTimeInterval: 25)
        }

        NSLog("CALLMATRIX glare placing call")
        try startVoiceCall(app)

        // Whichever side lost the tie-break ends up as callee and must accept.
        if let accept = waitForAcceptButton(app, timeout: 12) {
            NSLog("CALLMATRIX glare lost tie-break — accepting")
            accept.tap()
        }
        let endBtn = app.buttons["End call"]
        _ = endBtn.waitForExistence(timeout: 45)
        Thread.sleep(forTimeInterval: callSeconds)
        capture(app, "glare_in_call")
        if endBtn.exists, endBtn.isHittable { endBtn.tap() }
        Thread.sleep(forTimeInterval: 5)
    }

    /// BOOTSTRAP — pairs the two devices by sending one message to the peer key,
    /// which creates the conversation on BOTH sides. Run once on one sim before
    /// the call scenarios; `oshi://add` only opens a sheet and `oshi://chat` is
    /// an unimplemented TODO, so this is the reliable pairing path.
    func testBootstrapPairing() throws {
        try XCTSkipUnless(!peerKey.isEmpty,
                          "requires TEST_RUNNER_OSHI_PEER_KEY from the paired runner")
        let app = launchApp()
        settle(app)
        // Reaching ChatView by key IS the pairing: openChatByKey calls
        // addPendingConversation(peer) then navigates, so the conversation exists
        // locally afterwards. (There is no send button — NewMessageView.sendButton
        // at :610 is dead code; the Paste field's .onSubmit does the work.)
        let ok = try openChatByKey(app)
        capture(app, ok ? "bootstrap_in_chat" : "bootstrap_failed")
        XCTAssertTrue(ok, "typing the peer key did not open the conversation")
    }

    // MARK: Helpers

    private func launchApp() -> XCUIApplication {
        let app = XCUIApplication()
        // ⚠️ Deliberately NOT -OSHIScreens. That flag calls connectWallet() on every
        // cold launch, which mints a BRAND NEW identity each time — measured: three
        // launches produced three different public keys. Pairing cannot survive the
        // relaunch this test itself performs, so the peer key would be stale before
        // the first call. Without the flag the identity is stable across launches
        // (verified twice), which is what a real user gets.
        //
        // -hasSeenOnboarding reaches @AppStorage("hasSeenOnboarding") through
        // NSArgumentDomain, so onboarding is skipped without the demo path.
        // -callKitDisabled routes ringing through the in-app WebSocket path, since
        // CallKit/PushKit do not function in the Simulator.
        // `hasSeenOnboarding` alone only hides the setup UI; on a freshly erased
        // simulator it leaves IdentityManager empty, which makes a typed peer key
        // fail validation and the pairing bootstrap cannot open a conversation.
        // This hook creates an identity only when none exists, so it is stable over
        // the deliberate relaunches in the call matrix (unlike -OSHIScreens).
        app.launchArguments += ["-hasSeenOnboarding", "YES", "-OSHIAutoIdentity", "-callKitDisabled", "YES"]
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]

        addUIInterruptionMonitor(withDescription: "Permission alert") { alert in
            for label in ["Allow", "Allow While Using App", "OK", "Continue", "Don't Allow"] {
                let b = alert.buttons[label]
                if b.exists { b.tap(); return true }
            }
            if alert.buttons.count > 0 {
                alert.buttons.element(boundBy: alert.buttons.count - 1).tap(); return true
            }
            return false
        }
        app.launch()
        return app
    }

    private func settle(_ app: XCUIApplication) {
        _ = app.tabBars.firstMatch.waitForExistence(timeout: 60)
        app.tap()   // nudge the interruption monitor
        Thread.sleep(forTimeInterval: 4)
    }

    /// Open the conversation with the peer.
    ///
    /// Prefers an existing conversation row (fast, and what a returning user
    /// does). Falls back to the New → Paste → submit path, which is the only
    /// reliable way to reach a peer that has no message history yet: submitting
    /// a valid key calls openConversation() and navigates straight into ChatView.
    private func openPeerChat(_ app: XCUIApplication) throws {
        if inChat(app, timeout: 3) { return }   // app may have launched into it

        let tabBar = app.tabBars.firstMatch
        if tabBar.waitForExistence(timeout: 60) { tabBar.buttons.element(boundBy: 0).tap() }
        Thread.sleep(forTimeInterval: 2)

        // Prefer an existing row (created by a prior send). Wait generously — under
        // load the list is slow to populate, and falling through to the key-entry
        // flow too eagerly was landing on the wrong screen.
        let row = app.buttons["conversation_row_first"].exists
            ? app.buttons["conversation_row_first"]
            : app.otherElements["conversation_row_first"]
        // OSHI_PEER_BY_KEY=1: skip the first-row shortcut. On a machine running other
        // sessions' sims, the first row can be a LAN-mesh peer of ANOTHER simulator
        // (measured 2026-09-23: the caller dialed a non-X25519 mesh id, not its pair).
        let byKeyOnly = ProcessInfo.processInfo.environment["OSHI_PEER_BY_KEY"] == "1"
        if !byKeyOnly, row.waitForExistence(timeout: 25), row.isHittable {
            row.tap()
            if inChat(app, timeout: 10) { return }
        }

        // Fall back to resolving the peer by key through the New → Paste flow.
        if try openChatByKey(app) { return }
        dumpHierarchy(app, "could_not_open_peer_chat")
        // Throw so best-effort callers (recall re-call) can catch; top-level
        // callers leave it uncaught and fail the test.
        struct PeerChatUnreachable: Error {}
        throw PeerChatUnreachable()
    }

    /// True once the ChatView toolbar is up (its call-action toggle / voice button).
    private func inChat(_ app: XCUIApplication, timeout: TimeInterval) -> Bool {
        return app.buttons["Show call actions"].waitForExistence(timeout: timeout)
            || app.buttons["Hide call actions"].exists
            || app.buttons["Voice call"].exists
    }

    /// Deterministic New → Paste → submit path. Returns whether ChatView opened.
    ///
    /// Hard-won lessons baked in: (1) the New screen defaults to the QR *scan*
    /// sub-tab, and any stray tap there opens QRScannerView as a sheet whose
    /// "Public key…" field is a DIFFERENT control — so we select Paste only by an
    /// exact "Paste" match and never guess positionally. (2) We bind to the paste
    /// field by its EXACT placeholder and explicitly reject the scanner's field.
    /// (3) `typeText` fails with "no keyboard focus" if the tap didn't focus the
    /// field, so we confirm a keyboard came up (re-tapping once) before typing.
    @discardableResult
    private func openChatByKey(_ app: XCUIApplication) throws -> Bool {
        XCTAssertFalse(peerKey.isEmpty, "TEST_RUNNER_OSHI_PEER_KEY not set")
        let tabBar = app.tabBars.firstMatch
        guard tabBar.waitForExistence(timeout: 30) else { return false }
        tabBar.buttons.element(boundBy: 2).tap()          // New
        Thread.sleep(forTimeInterval: 3)

        // Select Paste by exact label only. If it isn't found, fail loudly rather
        // than tapping something that opens the scanner.
        let paste = app.buttons["Paste"]
        guard paste.waitForExistence(timeout: 10), paste.isHittable else {
            dumpHierarchy(app, "paste_subtab_not_found")
            return false
        }
        paste.tap()
        Thread.sleep(forTimeInterval: 2)

        let field = app.textFields["Paste or type the key here..."]
        guard field.waitForExistence(timeout: 10) else {
            dumpHierarchy(app, "paste_field_not_found")
            return false
        }
        // Focus, confirming the keyboard actually came up before typing.
        field.tap()
        if !app.keyboards.firstMatch.waitForExistence(timeout: 4) {
            field.tap()
            _ = app.keyboards.firstMatch.waitForExistence(timeout: 4)
        }
        field.typeText(peerKey + "\n")   // .onSubmit → openConversation()
        return inChat(app, timeout: 20)
    }

    /// Reveal the retractable action bar, then tap Voice call. Retries the reveal:
    /// under load the toggle tap or the bar animation is easily missed, and a
    /// one-shot attempt was silently skipping the whole scenario. A miss is a hard
    /// XCTFail, not a skip — a skipped scenario reads as a pass and hides a no-run.
    private func startVoiceCall(_ app: XCUIApplication) throws {
        let voice = app.buttons["Voice call"]
        for attempt in 1...4 {
            if voice.exists, voice.isHittable { voice.tap(); return }
            // The toggle label flips (Show/Hide) with state, so match either.
            let toggle = app.buttons["Show call actions"].exists
                ? app.buttons["Show call actions"]
                : app.buttons["Hide call actions"]
            if toggle.exists, toggle.isHittable {
                toggle.tap()
            } else if attempt == 1 {
                // Not even in a chat — surface why rather than blaming the bar.
                dumpHierarchy(app, "no_action_bar_toggle")
            }
            if voice.waitForExistence(timeout: 6), voice.isHittable { voice.tap(); return }
            NSLog("CALLMATRIX startVoiceCall retry \(attempt): voice button not ready")
            Thread.sleep(forTimeInterval: 2)
        }
        dumpHierarchy(app, "voice_call_button_unreachable")
        // Throw (not XCTFail) so a caller can decide whether this is fatal: the
        // primary scenarios let it propagate and fail the test, while the recall
        // re-call catches it as a known simulator limitation.
        struct VoiceButtonUnreachable: Error {}
        throw VoiceButtonUnreachable()
    }

    /// The accept button's a11y label differs for voice vs video.
    private func waitForAcceptButton(_ app: XCUIApplication, timeout: TimeInterval) -> XCUIElement? {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            for label in ["Accept voice call", "Accept video call"] {
                let b = app.buttons[label]
                if b.exists && b.isHittable { return b }
            }
            Thread.sleep(forTimeInterval: 0.5)
        }
        return nil
    }

    /// Attach the full element tree so a selector miss is debuggable from the
    /// .xcresult instead of guessing.
    private func dumpHierarchy(_ app: XCUIApplication, _ name: String) {
        let tree = app.debugDescription
        NSLog("CALLMATRIX HIERARCHY (%@):\n%@", name, tree)
        let att = XCTAttachment(string: tree)
        att.name = "hierarchy_\(name)"
        att.lifetime = .keepAlways
        add(att)
        capture(app, name)
    }

    private func capture(_ app: XCUIApplication, _ name: String) {
        let att = XCTAttachment(screenshot: app.screenshot())
        att.name = name
        att.lifetime = .keepAlways
        add(att)
    }
}

// MARK: - Two-simulator SHARE-SHEET end-to-end (Photos → OSHIShare → peer)
//
// WHAT THIS DRIVES
// ────────────────
// Sim A: pair with the peer, send one text (that is what CREATES the
//        conversation and, with it, the v2 ratchet session), background OSHI,
//        open Photos, share the seeded photo into OSHI, pick the peer.
// Sim B: sit in the conversation with A and watch for the photo to land.
//
// WHY A TEXT IS SENT FIRST — it is not padding, it is the precondition for the
// interesting branch:
//   · `OSHIShare` draws its "Send to…" list from `contacts_snapshot.json`, which
//     `MessageManager.updateConversations()` builds from `conversations` — i.e.
//     from MESSAGES. A merely-pending conversation (what `openChatByKey` alone
//     produces) is NOT in that file, so the picker would be empty and the sheet
//     would only offer "Open OSHI".
//   · `ShareViewController.attemptDirectSend` gate 3 requires
//     `V2SessionStore().hasSession(peerUserKey:)`. With no prior v2 traffic there
//     is no session, and the extension CORRECTLY falls back to queue-and-open.
// So without the text send this test would only ever exercise the fallback and
// would "prove" the foreground hop is mandatory, which would be a lie.
//
// THE VERDICT IS A FORK, NOT A PASS/FAIL
// ──────────────────────────────────────
// `finishSelection` has exactly two arms, and each has a matching NSLog:
//   .sent      → "[OSHIShare] ⚠️ sent from the extension (N envelope(s)) …"
//                label "Sent!"        → OSHI must STAY BACKGROUNDED
//   .fellBack  → "[OSHIShare] ⚠️ direct send declined (<reason>) — queueing …"
//                label "Open OSHI"    → OSHI is opened on purpose
// The test reads the LABEL (in-process, English pinned) and the app's
// `.state`, and asserts the two agree. Either fork is a legitimate result; a
// DISAGREEMENT (said "Sent!" then foregrounded anyway, or said "Open OSHI" and
// never opened) is the only thing that is actually a bug.
//
// ⚠️ WHAT A GREEN RUN DOES NOT PROVE — read this before quoting the result:
//   1. The Simulator does not enforce keychain access groups AT ALL. Gate 3's
//      `hasSession` read succeeding here says NOTHING about whether the
//      extension can read the ratchet on a real device, which is the entire
//      risk this code path carries. Only a device run answers that.
//   2. `recordDirectSend` writes `shareExtensionSentLog` and NOTHING reads it
//      (its own doc comment says so), so a direct send does not appear in the
//      SENDER's transcript. Absence of the photo on A is expected, not a defect.
//   3. Arrival on B is asserted as an image-count DELTA in the open chat plus a
//      screenshot. It proves "a new image bubble appeared", not "these exact
//      bytes". A byte-level assertion needs a hook the app does not expose.
//
// ENV (via `TEST_RUNNER_` prefix, stripped by xcodebuild):
//   TEST_RUNNER_OSHI_PEER_KEY   the OTHER sim's public key (required, both roles)
//   TEST_RUNNER_OSHI_WAIT_SECONDS  receiver watch window (default 240)
//   TEST_RUNNER_OSHI_SHARE_E2E=1  enable the paired two-simulator scenarios
//
// The simulator's SYSTEM language must be English: the share extension is a
// separate process and does NOT inherit the app's `-AppleLanguages` argument,
// so its labels follow the device locale. See share_e2e.sh.
final class ShareSheetE2EUITests: XCTestCase {

    private static let oshiBundleID  = "Moriceau-lab.Genesis"
    private static let photosBundleID = "com.apple.mobileslideshow"
    private static let springboardID  = "com.apple.springboard"

    /// One grep-able line per run, so the driver script can read the verdict out
    /// of the log next to the `[OSHIShare]` lines it is cross-checking.
    private func verdict(_ text: String) {
        NSLog("SHARE_E2E VERDICT %@", text)
        let att = XCTAttachment(string: text)
        att.name = "verdict"
        att.lifetime = .keepAlways
        add(att)
    }

    private var peerKey: String {
        ProcessInfo.processInfo.environment["OSHI_PEER_KEY"] ?? ""
    }
    private var waitSeconds: TimeInterval {
        TimeInterval(ProcessInfo.processInfo.environment["OSHI_WAIT_SECONDS"] ?? "") ?? 240
    }
    private var shareE2EEnabled: Bool {
        ProcessInfo.processInfo.environment["OSHI_SHARE_E2E"] == "1"
    }

    private func requireShareE2E() throws {
        try XCTSkipUnless(shareE2EEnabled,
                          "requires two concurrent runners; set TEST_RUNNER_OSHI_SHARE_E2E=1")
    }

    override func setUpWithError() throws {
        // A share-sheet miss must still leave the verdict + screenshots behind:
        // aborting on the first failed selector is how this test would end up
        // reporting nothing at all.
        continueAfterFailure = true
    }

    // MARK: - Sim A — sender

    func testSenderSharesPhotoToPeer() throws {
        // 🔧 (2026-08-21) Ce parcours est iOS-only: il pilote Photos et la
        // feuille de partage système, et utilise `XCUIDevice.press(_:)` qui
        // n'existe pas sur macOS. Sans ce saut, la CIBLE ENTIÈRE de tests UI ne
        // compilait plus pour Catalyst — donc le repli « simulateurs saturés →
        // lancer la suite sur Catalyst » était mort au moment précis où on en a
        // besoin. Sauter un test qui n'a pas de sens ici vaut mieux qu'empêcher
        // les tests unitaires de tourner du tout.
        #if targetEnvironment(macCatalyst)
        throw XCTSkip("Parcours iOS (Photos + feuille de partage): sans objet sur Catalyst.")
        #endif
        try requireShareE2E()
        try XCTSkipUnless(!peerKey.isEmpty,
                          "requires TEST_RUNNER_OSHI_PEER_KEY from the paired runner")

        let app = launchOSHI()
        settle(app)

        // ── 1. Pair + send one text. Both are preconditions (see header). ──
        guard try openChatByKey(app) else {
            capture(app, "A_00_pairing_failed")
            verdict("BLOCKED stage=pairing — could not reach ChatView by key")
            return XCTFail("pairing failed; nothing downstream would mean anything")
        }
        capture(app, "A_01_paired_chat")

        let marker = "sharee2e-\(Int(Date().timeIntervalSince1970))"
        let textSent = sendText(app, marker)
        capture(app, "A_02_text_sent")
        // Not fatal on its own — report it, because it fully explains a
        // fallback verdict further down and must not be mistaken for a bug in
        // the extension.
        if !textSent {
            verdict("WARN stage=seed_text — could not send the seed text; expect the FALLBACK arm (no v2 session, empty contact list)")
        }
        // Let the send land, the ratchet save, and updateConversations() rewrite
        // contacts_snapshot.json before the extension reads it.
        Thread.sleep(forTimeInterval: 12)

        // ── 2. Background OSHI, so "did it foreground?" has a real answer. ──
        // `oshi` reste déclaré INCONDITIONNELLEMENT: il sert plus bas. Seuls les
        // appels iOS-only sont exclus à la COMPILATION — un `XCTSkip` s'exécute,
        // il ne fait pas disparaître une ligne qui doit quand même compiler.
        let oshi = XCUIApplication(bundleIdentifier: Self.oshiBundleID)
        #if !targetEnvironment(macCatalyst)
        XCUIDevice.shared.press(.home)
        Thread.sleep(forTimeInterval: 3)
        XCTAssertNotEqual(oshi.state, .runningForeground,
                          "OSHI still foreground after Home — the foreground check below would be meaningless")
        #endif

        // ── 3. Photos → first photo → Share. ──
        let photos = XCUIApplication(bundleIdentifier: Self.photosBundleID)
        photos.launch()
        Thread.sleep(forTimeInterval: 4)
        dismissPhotosChrome(photos)
        capture(photos, "A_03_photos_open")

        guard openFirstPhoto(photos) else {
            capture(photos, "A_04_no_photo")
            verdict("BLOCKED stage=photos_open — no photo openable; was `simctl addmedia` run on this sim?")
            return XCTFail("no photo to share")
        }
        Thread.sleep(forTimeInterval: 2)
        capture(photos, "A_04_photo_open")

        guard tapShareButton(photos) else {
            dumpTree(photos, "A_05_no_share_button")
            verdict("BLOCKED stage=share_button — could not find the Share control in Photos (system UI, selectors are brittle)")
            return XCTFail("share button unreachable")
        }
        Thread.sleep(forTimeInterval: 4)
        capture(photos, "A_05_share_sheet")

        // ── 4. Find OSHI in the share sheet. This is the brittle step. ──
        guard tapOSHIInShareSheet(photos) else {
            dumpTree(photos, "A_06_oshi_not_in_sheet")
            verdict("BLOCKED stage=share_sheet_oshi — OSHI not reachable in the activity row. This is a TEST-DRIVING limit, not evidence the extension is broken; verify by hand.")
            return XCTFail("OSHI not found in share sheet")
        }
        Thread.sleep(forTimeInterval: 5)
        capture(photos, "A_06_extension_open")

        // ── 5. Pick the peer in the extension's "Send to…" list. ──
        // An EMPTY list is itself a finding: it means contacts_snapshot.json had
        // no entry for this peer, which is why the fallback arm exists.
        let picked = pickContactInExtension(photos)
        if !picked {
            dumpTree(photos, "A_07_no_contact_row")
            verdict("PARTIAL stage=contact_pick — the extension opened but offered NO contact row (contacts_snapshot.json had no entry for the peer). The sheet can only offer 'Open OSHI'; the direct-send path is unreachable by construction.")
            return
        }
        capture(photos, "A_07_contact_picked")

        // ── 6. Read which arm `finishSelection` took. ──
        let arm = awaitOutcomeLabel(photos, timeout: 60)
        capture(photos, "A_08_outcome")

        // ── 7. Independently: did OSHI come to the foreground? ──
        // Poll rather than sample once: `openMainApp()` is fired 1.6 s after the
        // label flips, and the launch itself takes a moment.
        var foregrounded = false
        let deadline = Date().addingTimeInterval(40)
        while Date() < deadline {
            if oshi.state == .runningForeground { foregrounded = true; break }
            Thread.sleep(forTimeInterval: 0.5)
        }
        Thread.sleep(forTimeInterval: 3)
        capture(XCUIApplication(bundleIdentifier: Self.springboardID), "A_09_after_share")

        // ── 8. Verdict. Either arm is legitimate; DISAGREEMENT is the bug. ──
        switch arm {
        case .sent:
            verdict("DIRECT_SEND path=sent foregrounded=\(foregrounded) marker=\(marker) — "
                  + "the extension reported 'Sent!'. Expect '[OSHIShare] ⚠️ sent from the extension' in the sim log. "
                  + "⚠️ The Simulator does not enforce keychain access groups, so this does NOT prove the extension can read the ratchet on a device.")
            XCTAssertFalse(foregrounded,
                           "extension said 'Sent!' but OSHI was pulled to the foreground anyway — that is the contradiction worth failing on")
        case .fellBack:
            verdict("FALLBACK path=queue_and_open foregrounded=\(foregrounded) marker=\(marker) — "
                  + "the extension reported 'Open OSHI'. Expect '[OSHIShare] ⚠️ direct send declined (<reason>)' in the sim log; "
                  + "'no_v2_session_readable_from_the_extension' is CORRECT behaviour when no v2 session exists yet, not a defect.")
            XCTAssertTrue(foregrounded,
                          "extension said 'Open OSHI' but OSHI never came forward — the queued photo would sit there unsent")
        case .unknown:
            dumpTree(photos, "A_08_unknown_outcome")
            verdict("PARTIAL stage=outcome_label foregrounded=\(foregrounded) — neither 'Sent!' nor 'Open OSHI' was observed within 60 s. "
                  + "Read the '[OSHIShare]' lines from the sim log for the real arm; the label match needs the DEVICE locale to be English.")
        }
    }

    // MARK: - Sim B — receiver

    /// Sits in the conversation with A and watches for a new image bubble.
    /// Run this FIRST (it is the long-lived side) and start the sender while it
    /// is watching.
    func testReceiverAwaitsSharedPhoto() throws {
        // 🔧 (2026-08-21) Ce parcours est iOS-only: il pilote Photos et la
        // feuille de partage système, et utilise `XCUIDevice.press(_:)` qui
        // n'existe pas sur macOS. Sans ce saut, la CIBLE ENTIÈRE de tests UI ne
        // compilait plus pour Catalyst — donc le repli « simulateurs saturés →
        // lancer la suite sur Catalyst » était mort au moment précis où on en a
        // besoin. Sauter un test qui n'a pas de sens ici vaut mieux qu'empêcher
        // les tests unitaires de tourner du tout.
        #if targetEnvironment(macCatalyst)
        throw XCTSkip("Parcours iOS (Photos + feuille de partage): sans objet sur Catalyst.")
        #endif
        try requireShareE2E()
        try XCTSkipUnless(!peerKey.isEmpty,
                          "requires TEST_RUNNER_OSHI_PEER_KEY from the paired runner")

        let app = launchOSHI()
        settle(app)
        guard try openChatByKey(app) else {
            capture(app, "B_00_pairing_failed")
            verdict("BLOCKED role=receiver stage=pairing — could not open the conversation with the sender")
            return XCTFail("receiver could not open the peer chat")
        }
        Thread.sleep(forTimeInterval: 6)

        // Baseline BEFORE the sender starts. `app.images` in a chat also counts
        // avatars and glyph decorations, so only the DELTA is meaningful — an
        // absolute count would be noise.
        let baseline = app.images.count
        NSLog("SHARE_E2E receiver baseline images=%d", baseline)
        capture(app, "B_01_baseline")

        var finalCount = baseline
        var arrived = false
        let deadline = Date().addingTimeInterval(waitSeconds)
        while Date() < deadline {
            finalCount = app.images.count
            if finalCount > baseline { arrived = true; break }
            Thread.sleep(forTimeInterval: 2)
        }
        // Let the blob finish decrypting/rendering before the evidence shot.
        Thread.sleep(forTimeInterval: 6)
        finalCount = app.images.count
        capture(app, "B_02_after_wait")

        if arrived {
            verdict("ARRIVED role=receiver images \(baseline)→\(finalCount) — a new image bubble appeared in the conversation. "
                  + "This proves a NEW IMAGE RENDERED, not that the bytes match the shared photo; see B_02_after_wait.")
        } else {
            verdict("NOT_ARRIVED role=receiver images \(baseline)→\(finalCount) after \(Int(waitSeconds))s — "
                  + "nothing new rendered. Check the sender's verdict first: on the FALLBACK arm the photo only leaves A once OSHI is opened there, so a miss here can be the sender's arm rather than a delivery bug.")
        }
        XCTAssertTrue(arrived, "no new image bubble arrived on the receiver within \(Int(waitSeconds))s")
    }

    // MARK: - Helpers (OSHI)

    /// Same launch contract as `CallMatrixUITests.launchApp`: deliberately NOT
    /// `-OSHIScreens`, which mints a fresh identity on every cold launch and
    /// would invalidate the peer key mid-test.
    private func launchOSHI() -> XCUIApplication {
        let app = XCUIApplication()
        // Keep the same stable-first-launch identity contract as CallMatrixUITests.
        // Skipping onboarding alone does not create an identity on a clean simulator.
        app.launchArguments += ["-hasSeenOnboarding", "YES", "-OSHIAutoIdentity", "-callKitDisabled", "YES"]
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        addUIInterruptionMonitor(withDescription: "Permission alert") { alert in
            for label in ["Allow", "Allow While Using App", "Allow Full Access",
                          "OK", "Continue", "Don't Allow"] {
                let b = alert.buttons[label]
                if b.exists { b.tap(); return true }
            }
            if alert.buttons.count > 0 {
                alert.buttons.element(boundBy: alert.buttons.count - 1).tap(); return true
            }
            return false
        }
        app.launch()
        return app
    }

    private func settle(_ app: XCUIApplication) {
        _ = app.tabBars.firstMatch.waitForExistence(timeout: 60)
        app.tap()
        Thread.sleep(forTimeInterval: 4)
    }

    private func inChat(_ app: XCUIApplication, timeout: TimeInterval) -> Bool {
        return app.buttons["Show call actions"].waitForExistence(timeout: timeout)
            || app.buttons["Hide call actions"].exists
            || app.buttons["Voice call"].exists
    }

    /// New → Paste → type key → submit. Lifted from `CallMatrixUITests` because
    /// its lessons are already paid for: select "Paste" by EXACT label (a
    /// positional guess opens the QR scanner sheet, whose key field is a
    /// different control), bind the field by its exact placeholder, and confirm
    /// the keyboard came up before typing or `typeText` fails on focus.
    @discardableResult
    private func openChatByKey(_ app: XCUIApplication) throws -> Bool {
        if inChat(app, timeout: 3) { return true }
        let tabBar = app.tabBars.firstMatch
        guard tabBar.waitForExistence(timeout: 30) else { return false }
        tabBar.buttons.element(boundBy: 2).tap()          // New
        Thread.sleep(forTimeInterval: 3)

        let paste = app.buttons["Paste"]
        guard paste.waitForExistence(timeout: 10), paste.isHittable else {
            dumpTree(app, "paste_subtab_not_found"); return false
        }
        paste.tap()
        Thread.sleep(forTimeInterval: 2)

        let field = app.textFields["Paste or type the key here..."]
        guard field.waitForExistence(timeout: 10) else {
            dumpTree(app, "paste_field_not_found"); return false
        }
        field.tap()
        if !app.keyboards.firstMatch.waitForExistence(timeout: 4) {
            field.tap()
            _ = app.keyboards.firstMatch.waitForExistence(timeout: 4)
        }
        field.typeText(peerKey + "\n")
        return inChat(app, timeout: 20)
    }

    /// Type `text` in the composer and press Send.
    ///
    /// ⚠️ The send button carries NO label and NO accessibilityIdentifier — it is
    /// a `ZStack` of a gradient `Circle` and an `Image(systemName: "arrow.up")`
    /// (ChatView.swift ~:2751), and it is rendered only `if !isEmpty`. Rather
    /// than guess its label, DIFF the button set before and after typing: the
    /// button that did not exist before the text did is the send button. That is
    /// selector-free and survives a redesign of the glyph.
    ///
    /// Also: the field is `TextField(axis: .vertical)`, so Return inserts a
    /// NEWLINE — it does not submit. Tapping is the only send.
    private func sendText(_ app: XCUIApplication, _ text: String) -> Bool {
        let field = app.textViews.firstMatch.exists ? app.textViews.firstMatch
                                                    : app.textFields.firstMatch
        guard field.waitForExistence(timeout: 10) else { return false }

        let before = Set(app.buttons.allElementsBoundByIndex.map { $0.label })
        field.tap()
        if !app.keyboards.firstMatch.waitForExistence(timeout: 4) { field.tap() }
        field.typeText(text)
        Thread.sleep(forTimeInterval: 1.5)

        let after = app.buttons.allElementsBoundByIndex
        for b in after where !before.contains(b.label) && b.isHittable {
            b.tap()
            Thread.sleep(forTimeInterval: 2)
            return true
        }
        // The diff found nothing new (the glyph may reuse an existing label) —
        // fall back to the explicit SF Symbol label before giving up.
        for name in ["arrow.up", "Send"] {
            let b = app.buttons[name]
            if b.exists, b.isHittable { b.tap(); Thread.sleep(forTimeInterval: 2); return true }
        }
        dumpTree(app, "send_button_not_found")
        return false
    }

    // MARK: - Helpers (Photos + share sheet — all SYSTEM UI, all brittle)

    private func dismissPhotosChrome(_ photos: XCUIApplication) {
        for label in ["Continue", "Get Started", "Not Now", "Later", "Dismiss", "OK"] {
            let b = photos.buttons[label]
            if b.exists, b.isHittable { b.tap(); Thread.sleep(forTimeInterval: 1.5) }
        }
    }

    private func openFirstPhoto(_ photos: XCUIApplication) -> Bool {
        // iOS 26 Photos: the grid is a collection view of images. Try cells, then
        // images, then a coordinate in the top-left of the grid.
        for candidate in [photos.cells.element(boundBy: 0),
                          photos.images.element(boundBy: 0)] {
            if candidate.waitForExistence(timeout: 8), candidate.isHittable {
                candidate.tap()
                if tapShareButtonExists(photos, timeout: 6) { return true }
            }
        }
        photos.windows.firstMatch
              .coordinate(withNormalizedOffset: CGVector(dx: 0.17, dy: 0.30)).tap()
        return tapShareButtonExists(photos, timeout: 8)
    }

    private func tapShareButtonExists(_ photos: XCUIApplication, timeout: TimeInterval) -> Bool {
        return photos.buttons["Share"].waitForExistence(timeout: timeout)
            || photos.buttons["ShareButton"].exists
    }

    private func tapShareButton(_ photos: XCUIApplication) -> Bool {
        for b in [photos.buttons["Share"], photos.buttons["ShareButton"]] {
            if b.waitForExistence(timeout: 8), b.isHittable { b.tap(); return true }
        }
        return false
    }

    /// The activity row is a horizontally scrolling collection owned by
    /// UIActivityViewController and hosted OUT OF PROCESS. It is queried through
    /// the host app AND through SpringBoard because which one owns the remote
    /// view is not something the test can rely on across iOS versions.
    private func tapOSHIInShareSheet(_ photos: XCUIApplication) -> Bool {
        let hosts = [photos, XCUIApplication(bundleIdentifier: Self.springboardID)]
        for attempt in 0..<4 {
            for host in hosts {
                for q in [host.buttons["OSHI"], host.cells["OSHI"],
                          host.staticTexts["OSHI"], host.otherElements["OSHI"]] {
                    if q.exists, q.isHittable { q.tap(); return true }
                }
                // Not visible yet: the app row scrolls, and OSHI may sit past the
                // fold or behind "More".
                let more = host.buttons["More"]
                if more.exists, more.isHittable { more.tap(); Thread.sleep(forTimeInterval: 2); continue }
                if attempt < 3 {
                    let w = host.windows.firstMatch
                    if w.exists {
                        w.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.62))
                         .press(forDuration: 0.05,
                                thenDragTo: w.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.62)))
                        Thread.sleep(forTimeInterval: 1.5)
                    }
                }
            }
        }
        return false
    }

    /// Tap the peer's row in the extension's "Send to…" table.
    /// Rows are labelled by alias, or by `shortKey()` = first6…last4 of the
    /// public key when there is no alias — so match on that, and only fall back
    /// to "the first row" (which the extension sorts most-recent-first, i.e. the
    /// peer we just messaged) when the key-derived label is not found.
    private func pickContactInExtension(_ photos: XCUIApplication) -> Bool {
        let hosts = [photos, XCUIApplication(bundleIdentifier: Self.springboardID)]
        let short = peerKey.count > 12
            ? String(peerKey.prefix(6)) + "…" + String(peerKey.suffix(4))
            : peerKey

        for host in hosts {
            _ = host.staticTexts["Send to…"].waitForExistence(timeout: 15)
            for q in [host.cells.staticTexts[short], host.staticTexts[short],
                      host.cells.containing(.staticText, identifier: short).firstMatch] {
                if q.exists, q.isHittable { q.tap(); return true }
            }
            // "Open OSHI" visible means the picker decided it has NO contacts —
            // report that as the finding it is instead of tapping it and calling
            // the fallback a result.
            if host.buttons["Open OSHI"].exists && host.cells.count == 0 { return false }
            let first = host.cells.element(boundBy: 0)
            if first.waitForExistence(timeout: 8), first.isHittable { first.tap(); return true }
        }
        return false
    }

    private enum ShareOutcome { case sent, fellBack, unknown }

    /// Poll for the label `finishSelection` sets. English only, because the
    /// extension follows the DEVICE locale, not the app's launch arguments —
    /// the driver script pins the simulator's system language for that reason.
    private func awaitOutcomeLabel(_ photos: XCUIApplication, timeout: TimeInterval) -> ShareOutcome {
        let hosts = [photos, XCUIApplication(bundleIdentifier: Self.springboardID)]
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            for host in hosts {
                if host.staticTexts["Sent!"].exists { return .sent }
                // ⚠️ staticTexts ONLY, never buttons. `share.open_oshi` is used
                // TWICE in this extension: as `previewLabel`'s text on the
                // fallback arm (a UILabel → staticText) and as `openAppButton`'s
                // title in the no-contacts EMPTY STATE (a UIButton → button).
                // Matching the button would report FALLBACK the instant the sheet
                // opened with an empty list — before any send was even attempted —
                // and that misreading would look exactly like a real result.
                if host.staticTexts["Open OSHI"].exists { return .fellBack }
            }
            Thread.sleep(forTimeInterval: 0.5)
        }
        return .unknown
    }

    // MARK: - Evidence

    private func capture(_ app: XCUIApplication, _ name: String) {
        let att = XCTAttachment(screenshot: app.screenshot())
        att.name = name
        att.lifetime = .keepAlways
        add(att)
    }

    private func dumpTree(_ app: XCUIApplication, _ name: String) {
        let tree = app.debugDescription
        NSLog("SHARE_E2E HIERARCHY (%@):\n%@", name, tree)
        let att = XCTAttachment(string: tree)
        att.name = "hierarchy_\(name)"
        att.lifetime = .keepAlways
        add(att)
        capture(app, name)
    }
}


// MARK: - Row density

/// Measures the height of a conversation row and a group row, so "more compact,
/// like Telegram" is a number rather than an impression.
///
/// Reference, measured off the Telegram chat list Hugo sent (iPhone 16 Pro,
/// 402x874 pt): ~52 pt of row pitch carrying a title plus up to two more lines.
final class RowDensityUITests: XCTestCase {

    private let shotsDir = "/Users/HUGOMORICEAU/Documents/Genesis/screenshots/row-density"

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-OSHIScreens",
                                "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        addUIInterruptionMonitor(withDescription: "permission alerts") { alert in
            for label in ["Ne pas autoriser", "Don't Allow", "Allow", "OK", "Autoriser"] {
                if alert.buttons[label].exists { alert.buttons[label].tap(); return true }
            }
            if alert.buttons.count > 0 {
                alert.buttons.element(boundBy: 0).tap(); return true
            }
            return false
        }
        app.launch()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 40), "no tab bar")
        app.tap()
        Thread.sleep(forTimeInterval: 6)
        return app
    }

    private func shoot(_ app: XCUIApplication, _ name: String) {
        let shot = app.screenshot()
        try? FileManager.default.createDirectory(atPath: shotsDir, withIntermediateDirectories: true)
        try? shot.pngRepresentation.write(to: URL(fileURLWithPath: "\(shotsDir)/\(name).png"))
        let a = XCTAttachment(screenshot: shot); a.name = name; a.lifetime = .keepAlways; add(a)
    }

    /// Rows carry `<prefix>_first`, then `<prefix>_1`, `_2`… Pitch is the gap
    /// between consecutive row origins, which is what the eye actually reads as
    /// density (content height plus whatever the List adds around it).
    private func report(_ app: XCUIApplication, prefix: String, label: String) {
        var frames: [CGRect] = []
        for i in 0..<6 {
            let id = i == 0 ? "\(prefix)_first" : "\(prefix)_\(i)"
            let el = app.buttons[id].exists ? app.buttons[id] : app.otherElements[id]
            guard el.exists else { break }
            frames.append(el.frame)
        }
        guard frames.count >= 2 else {
            print("DENSITY \(label): only \(frames.count) row(s) found, cannot measure")
            return
        }
        let pitches = zip(frames.dropFirst(), frames).map { $0.minY - $1.minY }
        let avg = pitches.reduce(0, +) / CGFloat(pitches.count)
        print(String(format: "DENSITY %-14s rows=%d  height=%.1f pt  pitch=%.1f pt  (Telegram ~52)",
                     (label as NSString).utf8String!, frames.count, frames[0].height, avg))
    }

    func testMeasureRowDensity() throws {
        let app = launch()

        // Demo mode can open straight into a conversation; walk back to the list.
        for _ in 0..<3 {
            if app.buttons["conversation_row_first"].exists
                || app.otherElements["conversation_row_first"].exists { break }
            let back = app.navigationBars.buttons.element(boundBy: 0)
            if back.exists, back.isHittable { back.tap(); Thread.sleep(forTimeInterval: 1.5) }
        }
        if app.buttons["segment_messages"].exists {
            app.buttons["segment_messages"].tap(); Thread.sleep(forTimeInterval: 1.2)
        }
        shoot(app, "chatlist")
        report(app, prefix: "conversation_row", label: "conversations")

        if app.buttons["segment_groups"].waitForExistence(timeout: 5) {
            app.buttons["segment_groups"].tap()
            Thread.sleep(forTimeInterval: 1.8)
            shoot(app, "groups")
            report(app, prefix: "group_row", label: "groups")
        } else {
            print("DENSITY groups: segment_groups not found")
        }
    }
}

// MARK: - Keyboard privacy, on a real device
//
// __KEYBOARD_PRIVACY_2026_10_01__ Runs on the owner's iPhone 14 (or any device):
// the Privacy › Keyboard section, its defaults, the explainer, no jailbreak
// warning on a stock phone, and typing in the 1:1 composer with the keyboard up.
// Screenshots are kept as attachments in the .xcresult.
final class KeyboardPrivacyDeviceUITests: XCTestCase {

    override func setUp() {
        continueAfterFailure = false
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }

    func testPrivacyKeyboardSection() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-OSHIScreens", "-OSHIPrivacySettings",
                                "-AppleLanguages", "(fr)", "-AppleLocale", "fr_FR"]
        app.launch()

        let block = app.switches["kbd.blockThirdParty"]
        var tries = 0
        while !block.exists && tries < 8 { app.swipeUp(); tries += 1 }
        XCTAssertTrue(block.waitForExistence(timeout: 5), "Block third-party keyboards toggle missing")
        // Default ON on a fresh install / unset key.
        XCTAssertEqual(block.value as? String, "1")
        let privateTyping = app.switches["kbd.privateTyping"]
        XCTAssertTrue(privateTyping.exists)
        // A stock, non-jailbroken phone must NOT get the red warning.
        XCTAssertFalse(app.otherElements["kbd.integrityWarning"].exists
                       || app.staticTexts["kbd.integrityWarning"].exists,
                       "Jailbreak warning shown on a stock device (false positive)")
        shot(app, "privacy-keyboard-section")

        let more = app.buttons["kbd.learnMore"]
        XCTAssertTrue(more.exists)
        more.tap()
        sleep(1)
        shot(app, "keyboard-explainer")
        app.swipeUp()
        shot(app, "keyboard-explainer-2")
    }

    func testComposerTypingWithKeyboard() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-OSHIScreens", "-OSHIOpenChat",
                                "-AppleLanguages", "(fr)", "-AppleLocale", "fr_FR"]
        app.launch()
        // A vertical-axis SwiftUI TextField surfaces as TextField or TextView
        // depending on the iOS version — match the identifier on any type, and
        // only once the chat is on screen.
        let composer = app.descendants(matching: .any)["chat.composer"]
        XCTAssertTrue(composer.waitForExistence(timeout: 15), "chat composer not found")
        composer.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5), "no keyboard appeared")
        composer.typeText("Test clavier privé")
        shot(app, "composer-typing")
        XCTAssertEqual(composer.value as? String, "Test clavier privé")
    }
}

// MARK: - LoRa node list, on a real device with real radios
//
// __LORA_NODE_SHOT_2026_10_23__ The Heltec and Seeed wiki articles each need one
// screenshot of the app's node list with the maker's boards in it. That picture
// cannot come from a simulator: there is no Bluetooth there, so the list would be
// empty, and filling it by hand would be a fabrication in a partner's
// documentation. So it is taken here, on the phone, with the radios on the desk.
//
// Deliberately NOT launched with `-OSHIScreens`: demo mode seeds invented content,
// and the whole point of this capture is that what it shows is real.
final class LoRaNodeShotUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = true
    }

    func testCaptureLoRaNodeList() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-hasSeenOnboarding", "YES", "-OSHIAutoIdentity", "-callKitDisabled", "YES"]
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]

        addUIInterruptionMonitor(withDescription: "Permission alert") { alert in
            for label in ["Allow", "Allow While Using App", "OK", "Continue", "Allow Once"] {
                let b = alert.buttons[label]
                if b.exists { b.tap(); return true }
            }
            if alert.buttons.count > 0 {
                alert.buttons.element(boundBy: alert.buttons.count - 1).tap(); return true
            }
            return false
        }

        app.launch()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 90), "main tab bar not found")
        app.tap()
        Thread.sleep(forTimeInterval: 3)

        // "More" is the last tab; Settings lives behind it.
        let tabBar = app.tabBars.firstMatch
        let lastTab = tabBar.buttons.count - 1
        if lastTab >= 0 { tabBar.buttons.element(boundBy: lastTab).tap() }
        Thread.sleep(forTimeInterval: 2)
        dumpHierarchy(app, "01_more")

        _ = tapAnything(app, ["Settings", "settings_row", "Réglages"], timeout: 8)
        Thread.sleep(forTimeInterval: 2)
        dumpHierarchy(app, "02_settings")

        // The row is `"lora.node.title".localized` = "LoRa Node", under the
        // "Long-Range Radio" section, which usually needs a scroll.
        var reached = tapAnything(app, ["LoRa Node"], timeout: 5)
        if !reached {
            for _ in 0..<6 {
                app.swipeUp()
                Thread.sleep(forTimeInterval: 1)
                if tapAnything(app, ["LoRa Node"], timeout: 2) { reached = true; break }
            }
        }
        dumpHierarchy(app, "03_after_lora_tap")
        XCTAssertTrue(reached, "could not reach the LoRa Node row — see hierarchy_02_settings")

        // Bluetooth discovery takes a few seconds, and the node list fills as the
        // radios answer. Capture twice so a slow node still lands in one of them.
        Thread.sleep(forTimeInterval: 12)
        capture(app, "04_lora_nodes_12s")
        Thread.sleep(forTimeInterval: 18)
        dumpHierarchy(app, "05_lora_nodes_30s")
    }

    /// Tap the first thing that matches any of these labels, whatever kind of
    /// element it turns out to be. SwiftUI rows land as buttons, cells or static
    /// text depending on the screen, and guessing wrong costs a whole device run.
    private func tapAnything(_ app: XCUIApplication, _ labels: [String], timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            for label in labels {
                for q in [app.buttons[label], app.cells[label], app.staticTexts[label],
                          app.otherElements[label], app.links[label]] where q.exists {
                    if q.isHittable { q.tap(); return true }
                    let c = q.firstMatch
                    if c.exists { c.tap(); return true }
                }
            }
            Thread.sleep(forTimeInterval: 0.5)
        } while Date() < deadline
        return false
    }

    private func dumpHierarchy(_ app: XCUIApplication, _ name: String) {
        let att = XCTAttachment(string: app.debugDescription)
        att.name = "hierarchy_\(name)"
        att.lifetime = .keepAlways
        add(att)
        capture(app, name)
    }

    private func capture(_ app: XCUIApplication, _ name: String) {
        let att = XCTAttachment(screenshot: app.screenshot())
        att.name = name
        att.lifetime = .keepAlways
        add(att)
    }
}

// MARK: - Chat scroll / position lab
//
// __SCROLL_LAB_2026_11_16__ Drives the real 1:1 (`ChatView`) and group
// (`GroupChatView`) threads seeded by `-OSHIScrollLab` (90 rows over four days,
// tall bubbles, links, photos) and asserts WHERE the rows end up: the newest row
// fully above the composer on open, after the keyboard, after a send, after an
// incoming message; the first unread on screen with `-OSHIScrollUnread`; and a
// reader who scrolled up NOT pulled down by an incoming message. Each step also
// writes a PNG to $SCROLL_LAB_DIR (default /tmp/oshi-scroll-lab) for a human look.
final class ChatScrollLabUITests: XCTestCase {

    override func setUpWithError() throws { continueAfterFailure = true }

    private var shotDir: String {
        ProcessInfo.processInfo.environment["SCROLL_LAB_DIR"] ?? "/tmp/oshi-scroll-lab"
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        let png = app.screenshot().pngRepresentation
        try? FileManager.default.createDirectory(atPath: shotDir, withIntermediateDirectories: true)
        try? png.write(to: URL(fileURLWithPath: shotDir).appendingPathComponent(name + ".png"))
        let a = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        a.name = name; a.lifetime = .keepAlways; add(a)
    }

    private func launch(group: Bool, _ extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-OSHIScreens", "-OSHIScrollLab", group ? "-OSHIOpenGroup" : "-OSHIOpenChat",
                                "-AppleLanguages", "(en)", "-AppleLocale", "en_US"] + extra
        app.launch()
        return app
    }

    private func composer(_ app: XCUIApplication, group: Bool) -> XCUIElement {
        app.descendants(matching: .any)[group ? "group.composer" : "chat.composer"].firstMatch
    }

    /// The bubble text containing `marker` (a SwiftUI Text surfaces as staticText).
    private func row(_ app: XCUIApplication, _ marker: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", marker)).firstMatch
    }

    /// Visible means: the whole frame lies between the nav bar and the top of
    /// whatever covers the bottom (composer, keyboard).
    private func fullyVisible(_ app: XCUIApplication, _ el: XCUIElement, group: Bool,
                              file: StaticString = #filePath, line: UInt = #line) -> Bool {
        guard el.exists else { return false }
        let f = el.frame
        var bottom = composer(app, group: group).frame.minY - 8   // composer field's own padding
        if app.keyboards.firstMatch.exists { bottom = min(bottom, app.keyboards.firstMatch.frame.minY) }
        let suggestions = app.staticTexts["Quick Replies"].firstMatch   // smart-reply bar above the composer
        if suggestions.exists { bottom = min(bottom, suggestions.frame.minY) }
        let top = app.navigationBars.firstMatch.exists ? app.navigationBars.firstMatch.frame.maxY : 0
        return f.minY >= top - 1 && f.maxY <= bottom + 1 && f.height > 0
    }

    private func report(_ app: XCUIApplication, _ el: XCUIElement, _ tag: String, group: Bool) {
        let c = composer(app, group: group).frame
        let k = app.keyboards.firstMatch.exists ? app.keyboards.firstMatch.frame.minY : -1
        print("SCROLLLAB \(tag) exists=\(el.exists) frame=\(el.exists ? el.frame : .zero) composerMinY=\(c.minY) kbMinY=\(k)")
    }

    private func waitOpen(_ app: XCUIApplication, group: Bool) {
        XCTAssertTrue(composer(app, group: group).waitForExistence(timeout: 20), "chat composer not found")
    }

    // 1 + 6: open lands on the newest row, stays there, no jump.
    private func openLandsAtBottom(group: Bool) {
        let p = group ? "group" : "1to1"
        let app = launch(group: group)
        waitOpen(app, group: group)
        shot(app, "\(p)-1-open-t0")
        if ProcessInfo.processInfo.environment["SCROLL_LAB_DUMP"] != nil { sleep(3); print("SCROLLLAB DUMP\n" + app.debugDescription) }
        let last = row(app, "LAST-ROW")
        usleep(400_000); shot(app, "\(p)-1-open-t0.4")
        // Bubbles render a "Decrypting…" placeholder for their first frames.
        XCTAssertTrue(last.waitForExistence(timeout: 5), "\(p): newest row never rendered")
        let f1 = last.frame
        sleep(3); shot(app, "\(p)-1-open-t3.4")
        report(app, last, "\(p) open", group: group)
        XCTAssertTrue(fullyVisible(app, last, group: group), "\(p): newest row not fully visible on open")
        XCTAssertEqual(f1.minY, last.frame.minY, accuracy: 2, "\(p): newest row moved after the first frames")
        // 6: scroll up through history to look at tall bubbles, links, day separators.
        for i in 1...3 { app.swipeDown(); usleep(600_000); shot(app, "\(p)-6-history-\(i)") }
        // Day separators: scroll until each header is on screen, shoot it.
        for header in ["Today", "Yesterday"] {
            let h = app.staticTexts[header].firstMatch
            var n = 0
            while !(h.exists && fullyVisible(app, h, group: group)) && n < 40 { app.swipeDown(velocity: .slow); n += 1 }
            usleep(500_000); shot(app, "\(p)-6-day-\(header)")
            XCTAssertTrue(h.exists, "\(p): no '\(header)' day separator")
        }
    }

    func test1to1OpenLandsAtBottom() { openLandsAtBottom(group: false) }

    /// Swipe up through the history a few times right after the open; a hang
    /// shows as "App event loop idle notification not received".
    private func swipeProbe(group: Bool) {
        let p = group ? "group" : "1to1"
        let app = launch(group: group)
        waitOpen(app, group: group)
        sleep(3)
        for i in 1...4 {
            let t0 = Date()
            app.swipeDown()
            let dt = Date().timeIntervalSince(t0)
            print("SCROLLLAB \(p) swipe \(i) took \(dt)s")
            XCTAssertLessThan(dt, 10, "\(p): app stopped responding after swipe \(i)")
            shot(app, "\(p)-probe-swipe-\(i)")
            if dt > 10 { return }
        }
    }

    func test1to1SwipeProbe() { swipeProbe(group: false) }
    func testGroupSwipeProbe() { swipeProbe(group: true) }

    /// The first drag after the open must move the list and leave it there.
    private func firstSwipeSticks(group: Bool) {
        let p = group ? "group" : "1to1"
        let app = launch(group: group)
        waitOpen(app, group: group)
        usleep(useconds_t(ProcessInfo.processInfo.environment["SCROLL_LAB_SWIPE_DELAY_MS"].flatMap { Int($0) } ?? 4000) * 1000)
        let last = row(app, "LAST-ROW")
        let y0 = last.frame.minY
        app.swipeDown()
        usleep(300_000); shot(app, "\(p)-1-first-swipe-t0.3")
        let y1 = last.frame.minY
        sleep(3); shot(app, "\(p)-1-first-swipe-t3")
        let y2 = last.frame.minY
        print("SCROLLLAB \(p) firstSwipe y0=\(y0) y1=\(y1) y2=\(y2)")
        XCTAssertGreaterThan(y2, y0 + 100, "\(p): first swipe did not move the list (or it was pulled back)")
        XCTAssertEqual(y1, y2, accuracy: 2, "\(p): list moved by itself after the swipe")
    }

    func test1to1FirstSwipeSticks() { firstSwipeSticks(group: false) }
    func testGroupFirstSwipeSticks() { firstSwipeSticks(group: true) }
    func testGroupOpenLandsAtBottom() { openLandsAtBottom(group: true) }

    // 2: 40 unread → land on the divider + first unread, and stay there.
    private func openOnFirstUnread(group: Bool) {
        let p = group ? "group" : "1to1"
        let app = launch(group: group, ["-OSHIScrollUnread"])
        waitOpen(app, group: group)
        shot(app, "\(p)-2-unread-t0")
        sleep(1)
        let first = row(app, "FIRST-UNREAD")
        XCTAssertTrue(first.waitForExistence(timeout: 5), "\(p): first unread row not rendered")
        shot(app, "\(p)-2-unread-t1")
        let f1 = first.frame
        sleep(4); shot(app, "\(p)-2-unread-t4")
        report(app, first, "\(p) unread", group: group)
        XCTAssertTrue(fullyVisible(app, first, group: group), "\(p): first unread not visible on open")
        XCTAssertEqual(f1.minY, first.frame.minY, accuracy: 2, "\(p): first unread moved after open")
        XCTAssertTrue(app.staticTexts["Unread messages"].exists || app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS[c] 'unread'")).firstMatch.exists, "\(p): no unread divider")
    }

    func test1to1OpenOnFirstUnread() { openOnFirstUnread(group: false) }
    func testGroupOpenOnFirstUnread() { openOnFirstUnread(group: true) }

    // 3: keyboard at the bottom keeps the newest row visible; scrolled up keeps the reading position.
    private func keyboard(group: Bool) {
        let p = group ? "group" : "1to1"
        let app = launch(group: group)
        waitOpen(app, group: group)
        sleep(2)
        composer(app, group: group).tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5), "no keyboard")
        sleep(1); shot(app, "\(p)-3-keyboard-at-bottom")
        let last = row(app, "LAST-ROW")
        report(app, last, "\(p) kb bottom", group: group)
        XCTAssertTrue(fullyVisible(app, last, group: group), "\(p): newest row hidden by the keyboard")
        // Dismiss, scroll up, then open the keyboard again.
        app.navigationBars.firstMatch.tap()
        if app.keyboards.firstMatch.exists { app.swipeDown() }
        sleep(1)
        app.swipeDown(); app.swipeDown(); sleep(1)
        let probe = row(app, "Message #6")   // some row in the upper part of the history
        shot(app, "\(p)-3-scrolled-up-before-kb")
        let lastBefore = row(app, "LAST-ROW").exists ? row(app, "LAST-ROW").frame : .zero
        composer(app, group: group).tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5), "no keyboard (2)")
        sleep(1); shot(app, "\(p)-3-scrolled-up-with-kb")
        report(app, row(app, "LAST-ROW"), "\(p) kb scrolled-up last (before \(lastBefore))", group: group)
        XCTAssertFalse(fullyVisible(app, row(app, "LAST-ROW"), group: group),
                       "\(p): opening the keyboard while reading history yanked to the bottom")
        _ = probe
    }

    func test1to1Keyboard() { keyboard(group: false) }
    func testGroupKeyboard() { keyboard(group: true) }

    // 4: send short then long (typed) — the new row ends fully visible, footer included.
    private func send(group: Bool) {
        let p = group ? "group" : "1to1"
        let app = launch(group: group)
        waitOpen(app, group: group)
        sleep(2)
        let field = composer(app, group: group)
        field.tap()
        field.typeText("SHORT-SEND ok")
        app.buttons["Send"].firstMatch.tap()
        sleep(2); shot(app, "\(p)-4-sent-short")
        let short = row(app, "SHORT-SEND")
        report(app, short, "\(p) short", group: group)
        XCTAssertTrue(fullyVisible(app, short, group: group), "\(p): short sent row not fully visible")
        let long = "LONG-SEND start. " + String(repeating: "Une phrase qui revient à la ligne. ", count: 4) + "LONG-END"
        field.tap()
        field.typeText(long)
        app.buttons["Send"].firstMatch.tap()
        sleep(3); shot(app, "\(p)-4-sent-long")
        let longRow = row(app, "LONG-END")
        report(app, longRow, "\(p) long", group: group)
        XCTAssertTrue(fullyVisible(app, longRow, group: group), "\(p): long sent row not fully visible")
    }

    func test1to1Send() { send(group: false) }

    /// A message taller than the screen (`-OSHISendLong`, sent through the real
    /// `sendMessage()`): its END and footer must be on screen afterwards.
    private func sendVeryLong(group: Bool) {
        let p = group ? "group" : "1to1"
        let app = launch(group: group, ["-OSHISendLong", "-OSHISendNow"])
        waitOpen(app, group: group)
        let end = row(app, "END OF THE LONG MESSAGE")
        XCTAssertTrue(end.waitForExistence(timeout: 15), "\(p): long message never sent")
        sleep(3); shot(app, "\(p)-4-sent-very-long")
        report(app, end, "\(p) very long", group: group)
        XCTAssertLessThanOrEqual(end.frame.maxY, composer(app, group: group).frame.minY - 8 + 1,
                                 "\(p): end of the long sent message hidden")
        XCTAssertGreaterThan(end.frame.maxY, 0, "\(p): view parked on the top of the long message")
    }

    func test1to1SendVeryLong() { sendVeryLong(group: false) }
    func testGroupSendVeryLong() { sendVeryLong(group: true) }
    func testGroupSend() { send(group: true) }

    // 5a: at the bottom, an incoming message is followed into view.
    private func incomingAtBottom(group: Bool) {
        let p = group ? "group" : "1to1"
        let app = launch(group: group, ["-OSHIIncomingAt", "7"])
        waitOpen(app, group: group)
        let inc = row(app, "END-INCOMING-1")
        XCTAssertTrue(inc.waitForExistence(timeout: 20), "\(p): incoming never arrived")
        sleep(2); shot(app, "\(p)-5-incoming-at-bottom")
        report(app, inc, "\(p) incoming bottom", group: group)
        XCTAssertTrue(fullyVisible(app, row(app, "INCOMING 1:"), group: group), "\(p): incoming row not fully visible")
    }

    func test1to1IncomingAtBottom() { incomingAtBottom(group: false) }
    func testGroupIncomingAtBottom() { incomingAtBottom(group: true) }

    // 5b: scrolled up, an incoming message must not move the reader.
    private func incomingScrolledUp(group: Bool) {
        let p = group ? "group" : "1to1"
        let app = launch(group: group, ["-OSHIIncomingAt", "14"])
        waitOpen(app, group: group)
        sleep(3)
        app.swipeDown(); app.swipeDown(); sleep(2)
        shot(app, "\(p)-5-scrolled-up-before")
        let probe = app.staticTexts.matching(NSPredicate(format: "label CONTAINS '#'")).allElementsBoundByIndex
            .first { fullyVisible(app, $0, group: group) }
        let before = probe?.frame
        sleep(14)   // past the scheduled arrival (a row far below the viewport may not be built at all)
        shot(app, "\(p)-5-scrolled-up-after")
        print("SCROLLLAB \(p) probe=\(probe?.label ?? "nil") before=\(String(describing: before)) after=\(String(describing: probe?.frame))")
        if let probe, let before { XCTAssertEqual(before.minY, probe.frame.minY, accuracy: 2, "\(p): reader was moved") }
        XCTAssertFalse(fullyVisible(app, row(app, "INCOMING 1:"), group: group), "\(p): reader was yanked to the incoming row")
    }

    func test1to1IncomingScrolledUp() { incomingScrolledUp(group: false) }
    func testGroupIncomingScrolledUp() { incomingScrolledUp(group: true) }

    // 7: search results (a filter over the whole thread) stay responsive while
    // typing in the search field and in the composer, are drawn as a bounded
    // window with a "+N more results" control, and open / scroll.
    // __FILTERED_RESULTS_WINDOW_2026_11_16__ A hang shows as "App event loop idle
    // notification not received" or a step over 10 s.
    private func searchWhileTyping(group: Bool) {
        let p = group ? "group" : "1to1"
        let app = launch(group: group, ["-OSHIScrollLabCount", "200", "-OSHIOpenSearch"])
        waitOpen(app, group: group)
        sleep(2)
        func timed(_ what: String, _ step: () -> Void) {
            let t0 = Date()
            step()
            let dt = Date().timeIntervalSince(t0)
            print("SCROLLLAB \(p) search \(what) took \(dt)s")
            XCTAssertLessThan(dt, 10, "\(p): app stopped responding during \(what)")
        }
        let field = app.textFields[group ? "group.search.field" : "chat.search.field"].firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), "\(p): search field not shown")
        timed("search typing") { field.tap(); field.typeText("Message") }
        sleep(1); shot(app, "\(p)-7-search-results")
        // Results are drawn, and only a window of them.
        let hit = app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'Message #'")).firstMatch
        XCTAssertTrue(hit.waitForExistence(timeout: 5), "\(p): no search result row drawn")
        let more = group
            ? app.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'earlier'")).firstMatch
            : app.buttons["chat.search.moreBelow"].firstMatch
        XCTAssertTrue(more.exists, "\(p): no control to draw more results (window not bounded?)")
        if !group {
            let counter = app.staticTexts.matching(NSPredicate(format: "label MATCHES '1/[0-9]+'")).firstMatch
            XCTAssertTrue(counter.exists, "\(p): no result counter")
            if counter.exists, let total = Int(counter.label.split(separator: "/").last ?? "") {
                XCTAssertGreaterThan(total, 50, "\(p): scroll lab should match more than one window")
            }
            // Next result: the list scrolls to it.
            timed("next result") {
                for _ in 0..<3 { app.buttons["Next result"].firstMatch.tap() }
            }
            XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label MATCHES '4/[0-9]+'")).firstMatch.exists,
                          "\(p): next result did not advance")
        }
        // Only a window of the matches is built (eager rows are all in the AX tree).
        let resultRows = { app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'Message #'")).count }
        let drawn = resultRows()
        print("SCROLLLAB \(p) search rows drawn=\(drawn)")
        XCTAssertGreaterThan(drawn, 0, "\(p): no result rows")
        XCTAssertLessThanOrEqual(drawn, 60, "\(p): \(drawn) result rows built — the window is not bounded")
        // Return closes the keyboard so the results get the screen (a swipe
        // starts mid-screen, which the keyboard would otherwise cover).
        field.typeText("\n")
        sleep(1)
        // Scroll the results (each swipe must come back).
        for i in 1...2 { timed("scroll results back \(i)") { if group { app.swipeUp() } else { app.swipeDown() } } }
        // Reach the "more" control and draw the next window (1:1: below the
        // window, which starts on the first match; group: "load earlier" above
        // the newest matches).
        var n = 0
        while !(more.exists && more.isHittable) && n < 30 {
            timed("scroll results \(n)") { if group { app.swipeDown() } else { app.swipeUp() } }
            n += 1
        }
        XCTAssertTrue(more.isHittable, "\(p): could not reach the 'more results' control")
        if more.isHittable {
            timed("more results") { more.tap() }
            sleep(1)
            let drawnAfter = resultRows()
            print("SCROLLLAB \(p) search rows after more=\(drawnAfter)")
            XCTAssertGreaterThan(drawnAfter, drawn, "\(p): 'more results' drew nothing more")
            XCTAssertLessThanOrEqual(drawnAfter, 110, "\(p): 'more results' drew more than one step")
        }
        shot(app, "\(p)-7-search-more")
        // Keep typing in the search field, then in the composer (it grows a
        // line), with the results still on screen.
        timed("search refine") { field.tap(); field.typeText(" #1") }
        sleep(1)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'Message #1'")).firstMatch
                        .waitForExistence(timeout: 5), "\(p): refined search shows no result")
        let comp = composer(app, group: group)
        timed("composer typing") {
            comp.tap()
            comp.typeText("SEARCH-TYPING a message long enough to wrap onto a second and a third line of the composer")
        }
        sleep(1); shot(app, "\(p)-7-search-composer")
        XCTAssertTrue(field.exists, "\(p): search bar gone")
        timed("search after composer") { field.tap(); field.typeText("0") }
        sleep(1); shot(app, "\(p)-7-search-final")
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'Message #10'")).firstMatch
                        .waitForExistence(timeout: 5), "\(p): final search shows no result")
    }

    func test1to1SearchWhileTyping() { searchWhileTyping(group: false) }
    func testGroupSearchWhileTyping() { searchWhileTyping(group: true) }
}

// MARK: - Mail reader fixtures
//
// Drives the DEBUG mail fixture harness (`MailFixtureHarness`): the app root is
// replaced by the mail reader fed from .eml files, so taps that matter for how a
// message is presented — "Load images", opening an attachment — are exercised on
// screen. Skipped unless TEST_RUNNER_MAIL_FIXTURE_DIR is set; screenshots are written
// to TEST_RUNNER_MAIL_SHOT_DIR.
final class MailFixtureUITests: XCTestCase {
    private var fixtureDir: String { ProcessInfo.processInfo.environment["MAIL_FIXTURE_DIR"] ?? "" }
    private var shotDir: String { ProcessInfo.processInfo.environment["MAIL_SHOT_DIR"] ?? NSTemporaryDirectory() }

    override func setUpWithError() throws {
        continueAfterFailure = true
        try XCTSkipIf(fixtureDir.isEmpty, "set TEST_RUNNER_MAIL_FIXTURE_DIR to run the mail fixture tests")
    }

    private func launch(_ fixture: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-OSHIMailFixtureDir", fixtureDir, "-OSHIMailFixture", fixture,
                                "-hasSeenOnboarding", "YES",
                                "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        return app
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        let png = app.screenshot().pngRepresentation
        try? png.write(to: URL(fileURLWithPath: shotDir).appendingPathComponent("\(name).png"))
    }

    func testLoadRemoteImagesButton() throws {
        let app = launch("13_remote_real")
        let button = app.buttons["mail.loadImages"]
        XCTAssertTrue(button.waitForExistence(timeout: 10), "no Load images bar on a mail with a remote image")
        Thread.sleep(forTimeInterval: 3)
        shot(app, "U_13_remote_blocked")
        button.press(forDuration: 1.2)
        Thread.sleep(forTimeInterval: 1)
        shot(app, "U_13_remote_menu")
        let once = app.buttons["Load images for this message"]
        if once.waitForExistence(timeout: 3) { once.tap() } else { app.tap(); button.tap() }
        Thread.sleep(forTimeInterval: 6)
        shot(app, "U_13_remote_loaded")
        XCTAssertFalse(app.buttons["mail.loadImages"].exists, "the bar should go once images are loaded")
    }

    func testOpenImageAttachmentInQuickLook() throws {
        let app = launch("05_image_attachments")
        let thumb = app.buttons["Saturday-1.jpg"].firstMatch
        XCTAssertTrue(thumb.waitForExistence(timeout: 10))
        Thread.sleep(forTimeInterval: 2)
        thumb.tap()
        Thread.sleep(forTimeInterval: 4)
        shot(app, "U_05_quicklook_jpg")
        XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 5), "QuickLook sheet did not open")
        app.buttons["Done"].tap()
        Thread.sleep(forTimeInterval: 1)
        let heic = app.buttons["IMG_4521.HEIC"].firstMatch
        if heic.waitForExistence(timeout: 3) {
            heic.tap()
            Thread.sleep(forTimeInterval: 4)
            shot(app, "U_05_quicklook_heic")
            app.buttons["Done"].tap()
        }
        let share = app.buttons["Share"].firstMatch
        if share.waitForExistence(timeout: 3) {
            share.tap()
            Thread.sleep(forTimeInterval: 3)
            shot(app, "U_05_share_sheet")
        }
    }

    func testOpenBigPhoto() throws {
        let app = launch("06_big_photo")
        let thumb = app.buttons["DSC_9001.jpg"].firstMatch
        XCTAssertTrue(thumb.waitForExistence(timeout: 20))
        Thread.sleep(forTimeInterval: 3)
        thumb.tap()
        Thread.sleep(forTimeInterval: 6)
        shot(app, "U_06_quicklook_big")
        XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 5), "QuickLook sheet did not open")
    }

    func testOpenPdfAndCalendar() throws {
        let app = launch("09_pdf_calendar")
        let pdf = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Facture'")).firstMatch
        XCTAssertTrue(pdf.waitForExistence(timeout: 10))
        pdf.tap()
        Thread.sleep(forTimeInterval: 4)
        shot(app, "U_09_quicklook_pdf")
        if app.buttons["Done"].waitForExistence(timeout: 5) { app.buttons["Done"].tap() }
        Thread.sleep(forTimeInterval: 1)
        let ics = app.buttons.matching(NSPredicate(format: "label CONTAINS 'invite.ics'")).firstMatch
        if ics.waitForExistence(timeout: 3) {
            ics.tap()
            Thread.sleep(forTimeInterval: 4)
            shot(app, "U_09_quicklook_ics")
        }
    }
}

// MARK: - Foldable / wide layout  __FOLDABLE_LAYOUT_2026_11_16__

/// The Chats tab at both widths, and the live change between them.
///
/// There is no iPhone Duo simulator in Xcode 26.x (Apple ships one in Xcode
/// 27.1's Device Hub), so the two poses are stood in for by what exists:
///   • narrow — any iPhone in portrait (compact width, like Duo closed);
///   • wide — an iPad (regular, like Duo open), or an iPhone Pro Max in
///     landscape, which reports a REGULAR width: rotating it mid-chat is a live
///     compact → regular → compact resize, the same event as unfolding and
///     folding, and the only one XCUITest can drive.
/// Run on several simulators; each test adapts its assertions to the width.
final class FoldableLayoutUITests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = true
        // Every scenario starts like a phone: folded (`FOLD_START=open` keeps
        // a Duo open, for the wide-only screen tests).
        if Self.isDuo {
            // Held upright: cover display portrait; opening it then gives the
            // inner panel in landscape (951 x 669), as the device is built.
            hostFold(ProcessInfo.processInfo.environment["FOLD_START"] == "open" ? "unfold" : "fold")
            XCUIDevice.shared.orientation = .portrait
            sleep(2)
        }
    }

    override func tearDown() {
        // Not on iPhone Duo: its orientation is shared by both panels, and
        // "portrait" there turns the OPEN panel upright, so the next fold
        // lands on a sideways cover display. The fold itself is reset in setUp.
        if !Self.isDuo { XCUIDevice.shared.orientation = .portrait }
        super.tearDown()
    }

    private var shotDir: String {
        ProcessInfo.processInfo.environment["FOLD_SHOT_DIR"] ?? "/tmp/oshi-fold"
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        // The screen, not the app: app.screenshot() comes back half-rotated
        // in landscape on the simulator.
        // iPhone Duo has two panels and `XCUIScreen.main` is the cover one
        // (black while open): keep the brightest screen's capture.
        let png = Self.litScreenshot()
        let tag = UIDevice.current.userInterfaceIdiom == .pad ? "ipad" : "iphone"
        let w = Int(app.windows.firstMatch.frame.width)
        let file = "\(tag)-\(w)w-\(name).png"
        try? FileManager.default.createDirectory(atPath: shotDir, withIntermediateDirectories: true)
        try? png.write(to: URL(fileURLWithPath: shotDir).appendingPathComponent(file))
        let a = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        a.name = file; a.lifetime = .keepAlways; add(a)
    }

    private func launch(_ extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-OSHIScreens", "-OSHIAllowLandscape",
                                "-AppleLanguages", "(en)", "-AppleLocale", "en_US"] + extra
            + MascotCaptureUITests.extraArgs   // $OSHI_UI_EXTRA_ARGS (OLED captures)
        app.launch()
        return app
    }

    private func el(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any)[id].firstMatch
    }

    /// Same threshold as `ThreadSplitMetrics` (regular width AND >= 640 pt).
    private func isWide(_ app: XCUIApplication) -> Bool {
        let w = app.windows.firstMatch.frame.width
        if UIDevice.current.userInterfaceIdiom == .pad { return w >= 640 }
        // iPhone Duo (iPhone19,4): regular width only OPEN — 951 x 669
        // (as opened) or 669 x 951 (turned upright). The cover display turned
        // sideways is 678 x 466, compact.
        if Self.isDuo {
            let h = app.windows.firstMatch.frame.height
            return w >= 640 && max(w, h) >= 900
        }
        // Other iPhones: only a Plus / Max in landscape reports a regular width.
        return w >= 900
    }

    static func litScreenshot() -> Data {
        guard isDuo, XCUIScreen.screens.count > 1 else { return XCUIScreen.main.screenshot().pngRepresentation }
        var best = Data(); var bestScore = -1.0
        for screen in XCUIScreen.screens {
            let shot = screen.screenshot()
            guard let cg = shot.image.cgImage else { continue }
            let score = Self.meanLuma(cg)
            if score > bestScore { bestScore = score; best = shot.pngRepresentation }
        }
        return best
    }

    private static func meanLuma(_ image: CGImage) -> Double {
        let w = 32, h = 32
        var px = [UInt8](repeating: 0, count: w * h * 4)
        guard let ctx = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return 0 }
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        var sum = 0
        for i in stride(from: 0, to: px.count, by: 4) { sum += Int(px[i]) + Int(px[i + 1]) + Int(px[i + 2]) }
        return Double(sum) / Double(w * h * 3)
    }

    static var isDuo: Bool {
        ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] == "iPhone19,4"
    }

    private func composerText(_ field: XCUIElement) -> String {
        (field.value as? String) ?? ""
    }

    private func hasFocus(_ field: XCUIElement) -> Bool {
        (field.value(forKey: "hasKeyboardFocus") as? Bool) ?? false
    }

    private func rotate(_ app: XCUIApplication, _ o: UIDeviceOrientation) {
        // On iPhone Duo the same width change is the real one: unfold for
        // "landscape" (wide), fold back for "portrait".
        if Self.isDuo {
            hostFold(o == .portrait ? "fold" : "unfold")
            return
        }
        XCUIDevice.shared.orientation = o
        sleep(4)
    }

    /// __DUO_POLISH_2026_11_18__ A test cannot fold the simulator itself: it
    /// asks the host. It writes `fold` / `unfold` (+ a sequence number) to
    /// `$FOLD_SHOT_DIR/fold-cmd`; `oshi-duo-foldd.sh` on the Mac performs it
    /// and answers with `fold-ack-<n>`. Without the helper this fails loudly
    /// instead of silently testing nothing.
    private static var foldSeq = 0
    private func hostFold(_ what: String) {
        Self.foldSeq += 1
        let n = Self.foldSeq
        let dir = URL(fileURLWithPath: shotDir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let ack = dir.appendingPathComponent("fold-ack-\(n)")
        try? FileManager.default.removeItem(at: ack)
        try? "\(what) \(n)".write(to: dir.appendingPathComponent("fold-cmd"), atomically: true, encoding: .utf8)
        let deadline = Date().addingTimeInterval(40)
        while !FileManager.default.fileExists(atPath: ack.path), Date() < deadline {
            usleep(250_000)
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: ack.path),
                      "host did not \(what) the iPhone Duo (is oshi-duo-foldd.sh running?)")
        sleep(4)
    }

    // 1. Two panes when wide; the phone push when narrow. Screens of every
    //    surface asked for: list, chat, group, mail, settings.
    func testTwoPaneAtRegularWidthAndScreens() {
        let app = launch()
        let row = el(app, "conversation_row_first")
        XCTAssertTrue(row.waitForExistence(timeout: 25), "no conversation list")
        sleep(2)
        let wide = isWide(app)
        shot(app, "1-list")
        if wide {
            XCTAssertTrue(el(app, "thread.placeholder").exists, "wide: no placeholder beside the list")
        }

        row.tap()
        let composer = el(app, "chat.composer")
        XCTAssertTrue(composer.waitForExistence(timeout: 10), "chat did not open")
        sleep(3)
        shot(app, "2-chat")
        if wide {
            XCTAssertTrue(el(app, "thread.detail").exists, "wide: chat not in a detail pane")
            XCTAssertTrue(row.exists && row.isHittable, "wide: the list must stay visible beside the chat")
            XCTAssertLessThan(row.frame.maxX, composer.frame.minX + 1, "wide: list must be LEFT of the chat")
            // Another row replaces the thread in place, no push.
            let second = el(app, "conversation_row_1")
            if second.exists {
                second.tap(); sleep(2)
                XCTAssertTrue(row.isHittable, "wide: list lost after switching thread")
                XCTAssertTrue(el(app, "chat.composer").exists)
            }
        } else {
            XCTAssertFalse(el(app, "thread.detail").exists, "narrow: there must be no detail pane")
            XCTAssertFalse(row.isHittable, "narrow: the chat must be pushed over the list")
        }

        // Groups
        if !wide { app.navigationBars.buttons.firstMatch.tap(); sleep(1) }
        el(app, "segment_groups").tap()
        let groupRow = el(app, "group_row_first")
        if groupRow.waitForExistence(timeout: 10) {
            sleep(1); shot(app, "3-groups")
            groupRow.tap()
            XCTAssertTrue(el(app, "group.composer").waitForExistence(timeout: 10), "group did not open")
            sleep(3); shot(app, "4-group-chat")
            if wide { XCTAssertTrue(groupRow.isHittable, "wide: group list must stay visible") }
            if !wide { app.navigationBars.buttons.firstMatch.tap(); sleep(1) }
        }

        // Mail
        el(app, "segment_email").tap()
        sleep(3); shot(app, "5-mail")

        // Settings (More tab → Settings)
        let more = app.buttons["More"].firstMatch
        if more.waitForExistence(timeout: 5) {
            more.tap(); sleep(1)
            let settings = app.buttons["Settings"].firstMatch
            if settings.waitForExistence(timeout: 5) {
                settings.tap(); sleep(3); shot(app, "6-settings")
                let privacy = app.buttons["Privacy"].firstMatch
                if privacy.waitForExistence(timeout: 3) { privacy.tap(); sleep(2); shot(app, "7-settings-privacy") }
            }
        }
    }

    // 2. Live resize mid-chat: same chat, same draft, keyboard still up.
    private func rotateKeepsThread(group: Bool) {
        let app = launch([group ? "-OSHIOpenGroup" : "-OSHIOpenChat"])
        let id = group ? "group.composer" : "chat.composer"
        let p = group ? "group" : "1to1"
        var composer = el(app, id)
        XCTAssertTrue(composer.waitForExistence(timeout: 25), "\(p): thread did not open")
        sleep(2)
        composer.tap()
        let draft = "FOLD-DRAFT \(p) keep me"
        composer.typeText(draft)
        sleep(1)
        shot(app, "8-\(p)-portrait-draft")
        XCTAssertTrue(composerText(composer).contains(draft))

        rotate(app, .landscapeLeft)
        composer = el(app, id)
        XCTAssertTrue(composer.waitForExistence(timeout: 5), "\(p): thread gone after rotating")
        sleep(1)
        shot(app, "9-\(p)-landscape")
        XCTAssertTrue(composerText(composer).contains(draft), "\(p): draft lost on rotation (got '\(composerText(composer))')")
        XCTAssertTrue(hasFocus(composer), "\(p): keyboard focus lost on rotation")
        if isWide(app) {
            XCTAssertTrue(el(app, "thread.detail").exists, "\(p): wide landscape should show the detail pane")
        }

        rotate(app, .portrait)
        composer = el(app, id)
        XCTAssertTrue(composer.waitForExistence(timeout: 5), "\(p): thread gone after rotating back")
        sleep(1)
        shot(app, "10-\(p)-portrait-again")
        XCTAssertTrue(composerText(composer).contains(draft), "\(p): draft lost rotating back (got '\(composerText(composer))')")
        XCTAssertTrue(hasFocus(composer), "\(p): keyboard focus lost rotating back")
        if !isWide(app) {
            XCTAssertFalse(el(app, "thread.detail").exists, "\(p): narrow again but still two panes")
        }
    }

    func test1to1RotateKeepsChatDraftAndKeyboard() { rotateKeepsThread(group: false) }
    func testGroupRotateKeepsChatDraftAndKeyboard() { rotateKeepsThread(group: true) }

    // 3. Live resize while reading history: the reader is not thrown to the bottom.
    private func rotateKeepsPlace(group: Bool) {
        let p = group ? "group" : "1to1"
        let app = launch(["-OSHIScrollLab", group ? "-OSHIOpenGroup" : "-OSHIOpenChat"])
        let id = group ? "group.composer" : "chat.composer"
        XCTAssertTrue(el(app, id).waitForExistence(timeout: 25), "\(p): thread did not open")
        sleep(3)
        for _ in 0..<3 { app.swipeDown(); usleep(500_000) }
        sleep(2)
        let window = app.windows.firstMatch.frame
        // A short row fully on screen; a tall "Long #" bubble can fill the
        // whole viewport, then scroll on a little.
        var probe: XCUIElement?
        for attempt in 0..<4 {
            if attempt > 0 { app.swipeDown(velocity: .slow); sleep(2) }
            let rows = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS 'Message #'")).allElementsBoundByIndex
            probe = rows.first { $0.frame.height < 300 && $0.frame.minY > window.minY + 120 && $0.frame.maxY < window.maxY - 160 }
            if probe != nil { break }
        }
        guard let probe else { XCTFail("\(p): no history row on screen"); return }
        let label = probe.label
        shot(app, "11-\(p)-reading-portrait")

        rotate(app, .landscapeLeft)
        sleep(2)
        shot(app, "12-\(p)-reading-landscape")
        let after = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
        let w2 = app.windows.firstMatch.frame
        XCTAssertTrue(after.exists && after.frame.maxY > w2.minY && after.frame.minY < w2.maxY,
                      "\(p): the row being read ('\(label.prefix(30))') is off screen after rotating")
        let last = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS 'LAST-ROW'")).firstMatch
        XCTAssertFalse(last.exists && last.frame.minY > w2.minY && last.frame.maxY < w2.maxY - 60,
                       "\(p): rotating while reading jumped to the newest message")
    }

    func test1to1RotateWhileReadingKeepsPlace() { rotateKeepsPlace(group: false) }
    func testGroupRotateWhileReadingKeepsPlace() { rotateKeepsPlace(group: true) }

    // 4. __DUO_POLISH_2026_11_18__ Settings: the page the user is on survives
    //    the resize both ways (one path shared by the stack and the split).
    func testSettingsRotateKeepsPage() {
        let app = launch(["-OSHISettingsRoot"])
        let privacy = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Privacy'")).firstMatch
        XCTAssertTrue(privacy.waitForExistence(timeout: 20), "settings: no Privacy row")
        sleep(1)
        shot(app, "13-settings-root")
        privacy.tap()
        sleep(2)
        let page = app.navigationBars.matching(NSPredicate(format: "identifier CONTAINS 'Privacy'")).firstMatch
        XCTAssertTrue(page.waitForExistence(timeout: 5), "settings: Privacy page did not open")
        shot(app, "14-settings-privacy-portrait")

        rotate(app, .landscapeLeft)
        shot(app, "15-settings-privacy-landscape")
        XCTAssertTrue(page.waitForExistence(timeout: 5), "settings: Privacy page lost on rotation")
        if isWide(app) {
            XCTAssertTrue(el(app, "settings.detail").exists, "settings: wide should show the detail pane")
            XCTAssertTrue(privacy.isHittable, "settings: wide should keep the hub beside the page")
        }

        rotate(app, .portrait)
        shot(app, "16-settings-privacy-portrait-again")
        XCTAssertTrue(page.waitForExistence(timeout: 5), "settings: Privacy page lost rotating back")
        if !isWide(app) {
            XCTAssertFalse(el(app, "settings.detail").exists, "settings: narrow again but still two panes")
        }
    }

    // 5. __DUO_POLISH_2026_11_18__ Mail: the open message survives the resize,
    //    in the detail pane when wide and pushed when narrow.
    func testMailRotateKeepsMessage() {
        let app = launch(["-OSHIMailDemo", "-OSHIMailDemoOpen"])
        let subject = app.staticTexts["Trip to Lisbon — final plan"].firstMatch
        XCTAssertTrue(subject.waitForExistence(timeout: 45), "mail: demo message did not open")
        sleep(2)
        shot(app, "17-mail-open-portrait")
        if isWide(app) { XCTAssertTrue(el(app, "mail.detail").exists, "mail: wide should show the detail pane") }

        rotate(app, .landscapeLeft)
        shot(app, "18-mail-open-landscape")
        XCTAssertTrue(app.staticTexts["Trip to Lisbon — final plan"].firstMatch.waitForExistence(timeout: 5),
                      "mail: message lost on rotation")
        if isWide(app) { XCTAssertTrue(el(app, "mail.detail").exists, "mail: wide should show the detail pane") }

        rotate(app, .portrait)
        shot(app, "19-mail-open-portrait-again")
        XCTAssertTrue(app.staticTexts["Trip to Lisbon — final plan"].firstMatch.waitForExistence(timeout: 5),
                      "mail: message lost rotating back")
    }

    // 6. __DUO_POLISH_2026_11_18__ Screens only: the "+" panel and a photo in
    //    the viewer, at whatever width this simulator has.
    func testAttachPanelAndPhotoViewerScreens() {
        let app = launch(["-OSHIOpenChat", "-OSHIMediaHeavy"])
        let composer = el(app, "chat.composer")
        XCTAssertTrue(composer.waitForExistence(timeout: 40), "chat did not open")
        sleep(3)
        shot(app, "20-chat-media")
        let plus = app.buttons["composer.attach"].firstMatch
        if plus.waitForExistence(timeout: 5) {
            plus.tap()
            _ = el(app, "attach.title").waitForExistence(timeout: 5)
            sleep(2)
            shot(app, "21-attach-panel")
            app.swipeDown()
            sleep(2)
            if el(app, "attach.title").exists {
                // A popover: tap its dismiss region (the panel now hangs over
                // the middle of the thread, where a blind tap would land in it).
                let dismiss = app.otherElements["PopoverDismissRegion"].firstMatch
                if dismiss.exists { dismiss.tap() }
                else { composer.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: -8)).tap() }
                sleep(1)
            }
            XCTAssertFalse(el(app, "attach.title").exists, "the + panel did not close")
        }
        // The newest photos are at the bottom of the thread, above the composer.
        // Walk from the newest; the thread can re-render under the query, so
        // each element is re-checked before its frame is read.
        // Photo bubbles are labelled "Photo" (media.a11y.photo), whatever
        // element type SwiftUI exposes them as.
        let images = app.descendants(matching: .any).matching(NSPredicate(format: "label == 'Photo'"))
        var photo: XCUIElement?
        let top = composer.frame.minY
        for i in stride(from: images.count - 1, through: 0, by: -1) {
            let e = images.element(boundBy: i)
            guard e.exists else { continue }
            let f = e.frame
            if f.width > 120, f.height > 120, f.maxY < top, f.minY > 0 { photo = e; break }
        }
        if let photo {
            photo.tap()
            sleep(3)
            shot(app, "22-photo-viewer")
        } else {
            XCTFail("no photo bubble on screen to open")
        }
    }
}
