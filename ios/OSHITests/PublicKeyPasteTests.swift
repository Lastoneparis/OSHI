//
//  PublicKeyPasteTests.swift
//  OSHITests
//
//  A shared key arrived corrupted in the field:
//
//      eAbMQUdW6UtufTtaHqDJtkC24fKa3c1nhaGN/muFE3A=0.0741 acre
//
//  The key was the LAST thing in the share text, so iOS's data detectors in the
//  receiving app latched onto the trailing base64 and appended a unit
//  conversion. Adding the contact then failed with no explanation, because the
//  pasted string was silently not a key.
//
//  These pin both halves of the fix: the wrapper that stops a detector, and the
//  salvage path for keys shared by an older build that had none.
//

import XCTest
import UIKit
import UserNotifications
import UniformTypeIdentifiers
@testable import OSHI

final class PublicKeyPasteTests: XCTestCase {

    private let key = "eAbMQUdW6UtufTtaHqDJtkC24fKa3c1nhaGN/muFE3A="

    private func extract(_ s: String) -> String {
        MessagesListView.extractPublicKeyFromQRPayload(s)
    }

    // MARK: The reported corruption

    /// THE bug. Autocorrect glued a unit conversion onto the end of the key.
    func testTrailingUnitConversionIsStripped() {
        XCTAssertEqual(extract(key + "0.0741 acre"), key)
    }

    func testOtherTrailingJunkAfterTheEquals() {
        XCTAssertEqual(extract(key + "1,234 km"), key)
    }

    // MARK: The wrapper

    func testBracketedKeyIsUnwrapped() {
        XCTAssertEqual(extract("⟦" + key + "⟧"), key)
    }

    /// People paste the whole message, not just the key.
    func testBracketedKeyIsFoundInsideAWholeSharedMessage() {
        let shared = """
        Add me on OSHI!

        https://oshi-messenger.org/add?key=SOMETHINGELSE

        My public key:
        ⟦\(key)⟧

        Copy everything between the brackets.
        """
        // I expected the URL branch to win here. It does NOT, and the real
        // behaviour is better: `URL(string:)` fails on a multi-line paste, so it
        // falls through to the bracket extraction and returns the genuine key
        // rather than a query parameter lifted out of the middle of a message.
        // Pinned because it is the case that actually happens — people paste the
        // whole "Add me on OSHI!" block, not a bare link.
        XCTAssertEqual(extract(shared), key)
    }

    func testBracketedKeyWithoutAnyURL() {
        XCTAssertEqual(extract("My public key:\n⟦\(key)⟧\n\nCopy it."), key)
    }

    func testWhitespaceInsideTheBracketsIsTrimmed() {
        XCTAssertEqual(extract("⟦ \(key) ⟧"), key)
    }

    // MARK: What must keep working

    func testPlainKeyIsUnchanged() {
        XCTAssertEqual(extract(key), key)
    }

    func testDeepLinkStillWins() {
        XCTAssertEqual(extract("https://oshi-messenger.org/add?key=" + key), key)
    }

    func testPercentEncodedDeepLink() {
        let encoded = key.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)!
        XCTAssertEqual(extract("https://oshi-messenger.org/add?key=" + encoded), key)
    }

    /// A key with no padding has no `=` — the salvage path must not damage it.
    func testKeyWithoutPaddingIsUntouched() {
        let unpadded = "eAbMQUdW6UtufTtaHqDJtkC24fKa3c1nhaGNmuFE3A"
        XCTAssertEqual(extract(unpadded), unpadded)
    }

    /// The salvage only fires when the tail is clearly not key material. A key
    /// can legitimately contain `=` in the middle of a longer padded form, and
    /// truncating a valid key would be worse than the bug it fixes.
    func testTailWithoutSpaceOrCommaIsNotTruncated() {
        let odd = key + "ABC"
        XCTAssertEqual(extract(odd), odd)
    }

    func testEmptyAndWhitespaceInputs() {
        XCTAssertEqual(extract(""), "")
        XCTAssertEqual(extract("   \n  "), "")
    }
}

// MARK: - Keyboard privacy, device integrity, clipboard hygiene
//
// __DEVICE_INTEGRITY_2026_10_01__ / __CLIPBOARD_HYGIENE_2026_10_01__
// WHY here: a new test file needs a project.pbxproj edit, and that file is
// being edited by other sessions. This file is already about the pasteboard.

final class KeyboardPrivacyIntegrityTests: XCTestCase {

    private let defaults = UserDefaults.standard
    private var savedBlock: Any?
    private var savedPrivate: Any?

    override func setUp() {
        super.setUp()
        savedBlock = defaults.object(forKey: KeyboardPrivacy.blockThirdPartyKeyboardsKey)
        savedPrivate = defaults.object(forKey: KeyboardPrivacy.privateKeyboardKey)
    }

    override func tearDown() {
        restore(savedBlock, KeyboardPrivacy.blockThirdPartyKeyboardsKey)
        restore(savedPrivate, KeyboardPrivacy.privateKeyboardKey)
        super.tearDown()
    }

    private func restore(_ value: Any?, _ key: String) {
        if let value { defaults.set(value, forKey: key) } else { defaults.removeObject(forKey: key) }
    }

    // (a) Defaults: the block is ON when never written, private typing OFF.
    func testKeyboardPrivacyDefaults() {
        defaults.removeObject(forKey: KeyboardPrivacy.blockThirdPartyKeyboardsKey)
        defaults.removeObject(forKey: KeyboardPrivacy.privateKeyboardKey)
        XCTAssertTrue(KeyboardPrivacy.blocksThirdPartyKeyboards)
        XCTAssertFalse(KeyboardPrivacy.privateTyping)
        XCTAssertTrue(KeyboardPrivacy.protectsNotificationReplies)
    }

    // (b) The app delegate refuses custom keyboards only while the block is on.
    func testExtensionPointBlocksKeyboardOnlyWhenEnabled() {
        let delegate = OshiAppDelegate()
        let app = UIApplication.shared

        defaults.set(true, forKey: KeyboardPrivacy.blockThirdPartyKeyboardsKey)
        XCTAssertFalse(delegate.application(app, shouldAllowExtensionPointIdentifier: .keyboard))

        defaults.set(false, forKey: KeyboardPrivacy.blockThirdPartyKeyboardsKey)
        XCTAssertTrue(delegate.application(app, shouldAllowExtensionPointIdentifier: .keyboard))
    }

    // (c) While a protection is on, "Reply" must NOT be typed inline in SpringBoard.
    func testReplyActionIsNotTextInputWhileProtected() {
        defaults.set(true, forKey: KeyboardPrivacy.blockThirdPartyKeyboardsKey)
        defaults.set(false, forKey: KeyboardPrivacy.privateKeyboardKey)
        let protectedAction = NotificationManager.makeReplyAction()
        XCTAssertFalse(protectedAction is UNTextInputNotificationAction)
        XCTAssertEqual(protectedAction.identifier, "REPLY_ACTION")
        XCTAssertTrue(protectedAction.options.contains(.foreground))

        defaults.set(false, forKey: KeyboardPrivacy.blockThirdPartyKeyboardsKey)
        defaults.set(true, forKey: KeyboardPrivacy.privateKeyboardKey)
        XCTAssertFalse(NotificationManager.makeReplyAction() is UNTextInputNotificationAction)

        // Both off: the inline field comes back (the user chose convenience).
        defaults.set(false, forKey: KeyboardPrivacy.blockThirdPartyKeyboardsKey)
        defaults.set(false, forKey: KeyboardPrivacy.privateKeyboardKey)
        let inline = NotificationManager.makeReplyAction()
        XCTAssertTrue(inline is UNTextInputNotificationAction)
        XCTAssertEqual(inline.identifier, "REPLY_ACTION")
    }

    // (c2) __LOCKED_MEANS_LOCKED_IOS_2026_10_28__ Neither branch may act on a LOCKED phone.
    //
    // Anatole reported this on Android (N12, 2026-10-28): the content was hidden on the
    // lock screen but the ACTIONS were not, so anyone holding the phone could reply as its
    // owner on a channel that authenticates the message as genuine. Android was fixed that
    // day and iOS was not checked — it had the same hole, in the category that actually
    // ships. A correctly-authenticated copy existed in `PushNotificationManager` whose own
    // comment records it has no caller: the right code was in the repository and nothing
    // called it.
    //
    // Asserted on BOTH branches, because the protection toggle changes the action TYPE and
    // a fix applied to one spelling would leave the other open.
    func testReplyNeverActsOnALockedPhone() {
        for (blockThirdParty, privateKeyboard) in [(true, false), (false, true), (false, false)] {
            defaults.set(blockThirdParty, forKey: KeyboardPrivacy.blockThirdPartyKeyboardsKey)
            defaults.set(privateKeyboard, forKey: KeyboardPrivacy.privateKeyboardKey)
            let action = NotificationManager.makeReplyAction()
            XCTAssertTrue(
                action.options.contains(.authenticationRequired),
                "Reply must require unlocking (blockThirdParty=\(blockThirdParty) private=\(privateKeyboard))"
            )
        }
    }

    // (c3) Marking as read is not a harmless local flag: it sends a read receipt the
    // sender will believe, so it must not run from the lock screen either. This one was
    // `options: []` — no foreground AND no authentication, so it executed in the
    // background on a locked phone.
    func testMarkAsReadNeverActsOnALockedPhone() {
        NotificationManager.shared.setupNotificationCategories()
        let expectation = XCTestExpectation(description: "categories read back")
        UNUserNotificationCenter.current().getNotificationCategories { categories in
            guard let message = categories.first(where: { $0.identifier == "MESSAGE_CATEGORY" }) else {
                XCTFail("MESSAGE_CATEGORY is not registered at all")
                expectation.fulfill()
                return
            }
            for action in message.actions {
                XCTAssertTrue(
                    action.options.contains(.authenticationRequired),
                    "\(action.identifier) can act on a locked phone"
                )
            }
            expectation.fulfill()
        }
        wait(for: [expectation], timeout: 10)
    }

    // (d) The simulator (like Mac Catalyst) is never flagged.
    func testDeviceIntegrityNeverFlagsSimulator() {
        #if targetEnvironment(simulator)
        XCTAssertTrue(DeviceIntegrity.isExemptEnvironment)
        #endif
        XCTAssertTrue(DeviceIntegrity.signals.isEmpty)
        XCTAssertFalse(DeviceIntegrity.detectedSigns)
        XCTAssertFalse(DeviceIntegrity.shouldWarnAtLaunch)
    }

    // (d') The heuristics themselves, with injected probes.
    func testDeviceIntegrityHeuristicsOnFakeEnvironment() {
        let clean = DeviceIntegrity.evaluate(
            fileExists: { _ in false },
            canWriteOutsideSandbox: { false },
            insertedLibraries: nil,
            loadedImages: ["/usr/lib/libSystem.B.dylib", "/System/Library/Frameworks/UIKit.framework/UIKit"]
        )
        XCTAssertEqual(clean, [])

        // Xcode's own debug injections on a device must not warn a developer.
        let xcodeRun = DeviceIntegrity.evaluate(
            fileExists: { _ in false },
            canWriteOutsideSandbox: { false },
            insertedLibraries: "/Developer/usr/lib/libBacktraceRecording.dylib:/Developer/usr/lib/libMainThreadChecker.dylib",
            loadedImages: []
        )
        XCTAssertEqual(xcodeRun, [])

        // XCTest on a device, sanitizers and GPU capture inject other libraries:
        // only hook-looking entries count (reviewer finding, 01/10).
        let xcodeTestRun = DeviceIntegrity.evaluate(
            fileExists: { _ in false },
            canWriteOutsideSandbox: { false },
            insertedLibraries: "/Developer/usr/lib/libXCTestBundleInject.dylib:@rpath/libclang_rt.asan_ios_dynamic.dylib:/usr/lib/libMTLCapture.dylib",
            loadedImages: []
        )
        XCTAssertEqual(xcodeTestRun, [])
        let jbPrefixInjection = DeviceIntegrity.evaluate(
            fileExists: { _ in false },
            canWriteOutsideSandbox: { false },
            insertedLibraries: "/var/jb/usr/lib/libinjector.dylib",
            loadedImages: []
        )
        XCTAssertEqual(jbPrefixInjection, [.injectedLibraryEnv("/var/jb/usr/lib/libinjector.dylib")])

        let jailbroken = DeviceIntegrity.evaluate(
            fileExists: { $0 == "/var/jb" || $0 == "/Applications/Sileo.app" },
            canWriteOutsideSandbox: { true },
            insertedLibraries: "/usr/lib/TweakInject.dylib",
            loadedImages: ["/usr/lib/libSystem.B.dylib", "/var/jb/usr/lib/libellekit.dylib", "@rpath/FridaGadget.dylib"]
        )
        XCTAssertTrue(jailbroken.contains(.suspiciousPath("/var/jb")))
        XCTAssertTrue(jailbroken.contains(.suspiciousPath("/Applications/Sileo.app")))
        XCTAssertTrue(jailbroken.contains(.sandboxWritable))
        XCTAssertTrue(jailbroken.contains(.injectedLibraryEnv("/usr/lib/TweakInject.dylib")))
        XCTAssertTrue(jailbroken.contains(.hookedImage("/var/jb/usr/lib/libellekit.dylib")))
        XCTAssertTrue(jailbroken.contains(.hookedImage("@rpath/FridaGadget.dylib")))
        XCTAssertFalse(jailbroken.contains(.hookedImage("/usr/lib/libSystem.B.dylib")))
    }

    /// __TICKER_RECEIVED_NO_AUTOLOAD_2026_11_20__ A received `@SYMBOL` must not
    /// make a network request just by being displayed. Source scan (SwiftUI).
    func testReceivedTickerChipDoesNotFetchOnDisplay() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        func src(_ p: String) throws -> String { try String(contentsOf: root.appendingPathComponent(p), encoding: .utf8) }
        let chip = try src("OSHI/TickerChipView.swift")
        XCTAssertTrue(chip.contains("guard autoLoad, frozen == nil else { return }"))
        XCTAssertTrue(chip.contains("var autoLoad: Bool = false"), "default must be no automatic fetch")
        XCTAssertTrue(try src("OSHI/ChatView.swift").contains("autoLoad: isFromCurrentUser)"))
        XCTAssertTrue(try src("OSHI/GroupViews.swift").contains("autoLoad: isMine)"))
    }

    /// __CONTACT_CARD_ALIAS_2026_11_20__ A received card's name never displaces
    /// an existing contact's name (which used to mark that contact stale).
    func testReceivedCardAliasCannotTakeAnExistingContactsName() {
        let existing = ["mum-key": "Maman", "bob-key": "Bob"]
        XCTAssertNil(MessagesListView.cardAliasToSave("maman ", for: "attacker-key", existing: existing))
        XCTAssertNil(MessagesListView.cardAliasToSave("  ", for: "attacker-key", existing: existing))
        XCTAssertEqual(MessagesListView.cardAliasToSave("Carol", for: "carol-key", existing: existing), "Carol")
        XCTAssertEqual(MessagesListView.cardAliasToSave("Bob", for: "bob-key", existing: existing), "Bob",
                       "re-saving a contact's own name is fine")
        XCTAssertEqual(MessagesListView.cardAliasToSave("Ev\u{0}e\n", for: "e", existing: [:]), "Eve")
        XCTAssertEqual(MessagesListView.cardAliasToSave(String(repeating: "x", count: 200), for: "x", existing: [:])?.count, 64)
    }

    /// __MAC_CAPTURE_SHIELD_2026_11_20__ `shield` sets NSWindowSharingNone on
    /// anything exposing `sharingType`, and is a no-op (never a KVC crash) on
    /// anything that does not.
    func testMacCaptureShieldSetsSharingNoneAndNeverCrashes() {
        let w = MacCaptureShieldFakeWindow()
        XCTAssertTrue(MacCaptureShield.shield(w))
        XCTAssertEqual(w.sharingType, MacCaptureShield.sharingNone)
        XCTAssertFalse(MacCaptureShield.shield(NSObject()))
        MacCaptureShield.shared.applyToAllWindows()   // no NSApplication on iOS: must be a no-op
    }

    /// A Cashu token is a bearer note: it must go through `SecureClipboard`,
    /// never the plain, Universal-Clipboard-synced pasteboard.
    func testCashuTokenCopyUsesTheSecureClipboard() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let src = try String(contentsOf: root.appendingPathComponent("OSHI/CashuTokenCardView.swift"), encoding: .utf8)
        XCTAssertFalse(src.contains("UIPasteboard.general.string = token.raw"))
        XCTAssertTrue(src.contains("SecureClipboard.copy(token.raw)"))
    }

    // (e) Sensitive copies: this device only (no Universal Clipboard), 60 s.
    func testSecureClipboardOptions() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let options = SecureClipboard.options(now: now)
        XCTAssertEqual(options[.localOnly] as? Bool, true)
        XCTAssertEqual(options[.expirationDate] as? Date, now.addingTimeInterval(60))
        XCTAssertEqual(SecureClipboard.lifetime, 60)

        let items = SecureClipboard.items(for: "secret message")
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items.first?[SecureClipboard.textType] as? String, "secret message")
    }

    // __MAC_COPY_WROTE_A_TYPE_NOBODY_READS_2026_11_06__
    //
    // These two tests existed before the bug was reported, and BOTH passed on a
    // Mac where Copy put nothing any app could paste. They are the reason it
    // survived, so they are worth reading as a pair:
    //
    //   * `testSecureClipboardOptions` asserted
    //     `items.first?[UTType.plainText.identifier]` — the implementation's own
    //     constant, handed back to itself. Whatever UTI `items(for:)` chose, the
    //     lookup used the same one, so the assertion could never disagree with
    //     the code. It now pins the LITERAL wire string instead, below.
    //   * `testSecureClipboardCopyIsReadableBackAsString` passed because the only
    //     reader was `UIPasteboard`, which resolves the abstract parent UTI
    //     `public.plain-text`. AppKit does not. So the round-trip was real and
    //     proved nothing about the paste that was actually failing.
    //
    // What was broken — measured on this Mac, Catalyst app writing, a separate
    // AppKit process reading: `NSPasteboard.string(forType: .string)` → nil,
    // `availableType(from: [.string])` → nil, `pbpaste` → nothing.
    func testTheCopiedTypeIsTheOneOtherAppsAskFor() {
        // Not UTType.anything: the point is the string that goes on the wire.
        // `NSPasteboard.PasteboardType.string` is this exact value, and it is
        // what TextEdit, Safari, Notes, Mail and pbpaste ask for.
        XCTAssertEqual(SecureClipboard.textType, "public.utf8-plain-text")

        // The trap itself, pinned so this file explains its own history: the
        // obvious-looking constant is the ABSTRACT PARENT, with no data
        // representation, and AppKit never resolves it.
        XCTAssertEqual(UTType.plainText.identifier, "public.plain-text")
        XCTAssertNotEqual(SecureClipboard.textType, UTType.plainText.identifier,
                          "copy is back on the abstract UTI — ⌘V in any other Mac app will paste nothing")
    }

    func testSecureClipboardCopyIsReadableBackAsString() {
        let board = UIPasteboard.withUniqueName()
        defer { UIPasteboard.remove(withName: board.name) }
        SecureClipboard.copy("hello from OSHI", to: board)
        XCTAssertEqual(board.string, "hello from OSHI")
        XCTAssertTrue(board.hasStrings)
        // The half the old round-trip was missing: not just "UIPasteboard can
        // read it back", but "the type an AppKit reader asks for is present".
        XCTAssertTrue(board.types.contains("public.utf8-plain-text"),
                      "on the pasteboard: \(board.types)")
    }
}

// __DEVICE_INTEGRITY_2026_10_01__ The real-hardware check: on a stock (non-jailbroken)
// iPhone running these tests from Xcode — which injects XCTest/debug libraries — the
// detector must report NOTHING. Skipped on the simulator, where it is exempt anyway.
final class DeviceIntegrityOnDeviceTests: XCTestCase {
    func testStockDeviceHasNoSignals() throws {
        #if targetEnvironment(simulator)
        throw XCTSkip("Only meaningful on a physical device")
        #else
        XCTAssertEqual(DeviceIntegrity.signals, [], "False positive on a stock device: \(DeviceIntegrity.signals)")
        XCTAssertFalse(DeviceIntegrity.detectedSigns)
        #endif
    }
}

/// Stand-in exposing `sharingType` like NSWindow does.
final class MacCaptureShieldFakeWindow: NSObject { @objc dynamic var sharingType: Int = 1 }

/// __OFFLINE_ONLY_FUTURE_AT_2026_11_20__ A peer's `at` is clamped to now + 5 min.
final class OfflineOnlyFutureTimestampTests: XCTestCase {
    func testPeerTimeIsClampedToNowPlusSkew() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        XCTAssertEqual(OfflineOnlyStore.clampedPeerTime(32_503_680_000, now: now), 1_800_000_300)
        XCTAssertEqual(OfflineOnlyStore.clampedPeerTime(1_799_999_000, now: now), 1_799_999_000)
        XCTAssertEqual(OfflineOnlyStore.clampedPeerTime(.infinity, now: now), 1_800_000_000)
        XCTAssertEqual(OfflineOnlyStore.clampedPeerTime(.nan, now: now), 1_800_000_000)
    }

    @MainActor
    func testFarFutureRequestDoesNotFreezeLaterChanges() {
        let store = OfflineOnlyStore.shared
        let conv = "offline-only-future-test-\(UUID().uuidString)"
        let now = Date()
        store.applyIncoming(OfflineOnlyRequest(on: true, at: Date(timeIntervalSince1970: 32_503_680_000)),
                            for: conv, now: now)
        XCTAssertTrue(store.isOfflineOnly(conv))
        // The peer's genuine "off" ten minutes later must still apply.
        store.applyIncoming(OfflineOnlyRequest(on: false, at: now.addingTimeInterval(600)),
                            for: conv, now: now.addingTimeInterval(600))
        XCTAssertFalse(store.isOfflineOnly(conv))
        store.set(false, for: conv)
    }
}

/// __PINS_UNION_2026_11_20__ iOS, Android and desktop pin the same union.
final class CertificatePinUnionTests: XCTestCase {
    func testIOSPinsAreTheCrossPlatformUnion() {
        XCTAssertEqual(V2Client.apiSPKIPins, [
            "0nAG9HjNRcCEVoDETqe5BKVXl97VZqmyVK+VikDj1r4=",
            "tcs1FWdgXPmNWGfDyxW1PXxrJ6C5uP1xyppLl1E/Wdg=",
            "brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=",
            "s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=",
            "sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=",
        ])
    }
}

