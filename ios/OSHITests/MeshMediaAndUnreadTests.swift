import XCTest
@testable import OSHI

/// Two fixes of 2026-11-07, both of the same family: a capability that existed
/// and a path to it that was wrong.
///
/// `__MEDIA_IN_THE_CLEAR_ON_THE_LAN_2026_11_07__` — iOS still base64'd photos
/// into a JSON frame on a TLS-less TCP socket when broadcasting to Android
/// peers, 43 days after Android deleted its own copy of that code and wrote
/// "mirrors iOS" in the commit comment.
///
/// `__UNREAD_HAD_NO_LINE_2026_11_07__` + `__UNREAD_ANCHOR_CANNOT_OUTRUN_THE_WINDOW__`
/// — opening a group landed you on the first unread (by design, since 17/10)
/// and drew nothing to say so, and the anchor was searched over the whole
/// history while only the newest 50 messages are rendered.
///
/// These are SOURCE-SCANNING guards. Neither fact is reachable from a unit
/// test: one is the absence of a function and of its call, the other is SwiftUI
/// view structure. Comments are stripped before every search, because the
/// prose above would otherwise satisfy half of these assertions.
final class MeshMediaAndUnreadTests: XCTestCase {

    // MARK: - __MEDIA_IN_THE_CLEAR_ON_THE_LAN_2026_11_07__

    /// The three senders that wrote a payload to a plain TCP socket. Deleted,
    /// not deprecated: they WRITE, so leaving them callable keeps the leak one
    /// line away. (Android could keep its counterpart — that one only builds a
    /// string.)
    func testTheClearTextSendersDoNotExist() throws {
        let mesh = try source("OSHI/CrossPlatformMesh.swift")
        for fn in ["func sendMedia(", "func sendLocation(", "func sendDocument("] {
            XCTAssertFalse(mesh.contains(fn),
                           "\(fn) is back in CrossPlatformMesh — it base64s a payload onto an "
                         + "NWParameters.tcp connection with no TLS. Media, location and documents "
                         + "are already inside encryptedContent; there is nothing for it to do.")
        }
    }

    /// The call site, pinned separately from the declaration. A guard on the
    /// function alone would stay green if someone re-added a caller for a frame
    /// built inline — and pinning a declaration instead of a call is exactly the
    /// mistake `scheduleReconnectSweep` caught yesterday.
    func testNothingInTheMeshManagerSendsMediaInTheClear() throws {
        let manager = try source("OSHI/MeshNetworkManager.swift")
        XCTAssertFalse(manager.contains("sendMedia("),
                       "MeshNetworkManager calls sendMedia again. The Android-broadcast loop "
                     + "must fall through to relayMessage(meshWireSafe()) like every other path.")
        // NOT a ban on the string: `case "MEDIA_MESSAGE":` is the RECEIVE switch
        // and must survive, or every peer on an older build loses its photos.
        // Asserted positively for that reason — the first draft of this test
        // forbade the string outright and went red on the compatibility path it
        // was supposed to protect.
        XCTAssertTrue(manager.contains("case \"MEDIA_MESSAGE\":"),
                      "the legacy MEDIA_MESSAGE receive case is gone — peers on older builds "
                    + "still send that frame and would silently lose media")
    }

    /// The broadcast loop must actually reach the encrypted branch. Proving the
    /// leak is gone is not the same as proving the photo still goes out: the
    /// branch used to `continue` past this, which is why deleting it alone
    /// would have been a silent feature loss if the bytes were not in the
    /// ciphertext.
    func testTheAndroidBroadcastLoopEndsAtTheEncryptedSend() throws {
        let manager = try source("OSHI/MeshNetworkManager.swift")
        let loop = try slice(manager,
                             from: "for androidPeer in CrossPlatformMesh.shared.getAndroidPeers()",
                             upTo: "if !sentSuccessfully {")
        XCTAssertTrue(loop.contains("message.meshWireSafe()"),
                      "the Android broadcast loop no longer sends the encrypted wire copy")
        XCTAssertTrue(loop.contains("relayMessage("),
                      "the Android broadcast loop no longer calls relayMessage")
        XCTAssertFalse(loop.contains("continue"),
                       "a `continue` is back in the loop — that is how the media branch used to "
                     + "skip the encrypted send for every message carrying an attachment.")
    }

    /// Why the deletion is safe, asserted rather than asserted-in-prose: the
    /// bytes are in the plaintext that gets encrypted.
    func testAnAttachmentIsEncodedIntoTheContentThatGetsEncrypted() throws {
        let mm = try source("OSHI/MessageManager.swift")
        XCTAssertTrue(mm.contains("\"content\": mediaData.base64EncodedString()"),
                      "MessageManager no longer embeds the media bytes in the message content. "
                    + "If that moved, meshWireSafe() stripping mediaAttachment DOES lose the "
                    + "photo and the clear-text deletion above would be a feature regression.")
    }

    // MARK: - __UNREAD_HAD_NO_LINE_2026_11_07__

    /// The divider exists, is localised, and is a ROW in the message list.
    func testTheGroupListDrawsAnUnreadDivider() throws {
        let views = try source("OSHI/GroupViews.swift")
        XCTAssertTrue(views.contains("\"chat_unread_divider\".localized"),
                      "the group unread divider is gone — landing above the newest message with "
                    + "nothing on screen to explain it is what got reported as a bug")
        XCTAssertTrue(views.contains("unreadDividerId"), "the divider has no stable id to scroll to")
    }

    func testTheOneToOneListDrawsTheSameDivider() throws {
        let chat = try source("OSHI/ChatView.swift")
        XCTAssertTrue(chat.contains("\"chat_unread_divider\".localized"),
                      "1:1 threads land on the first unread too and must say so as well")
    }

    /// Every locale carries the key. A `.localized` miss shows the raw key —
    /// the Vallon rating-prompt failure.
    func testTheDividerStringExistsInEveryLocale() throws {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        dir = dir.deletingLastPathComponent().appendingPathComponent("OSHI")
        let locales = try FileManager.default
            .contentsOfDirectory(atPath: dir.path)
            .filter { $0.hasSuffix(".lproj") }
        XCTAssertGreaterThanOrEqual(locales.count, 30, "expected the full locale set, found \(locales.count)")
        for loc in locales {
            let url = dir.appendingPathComponent(loc).appendingPathComponent("Localizable.strings")
            let text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
            XCTAssertTrue(text.contains("\"chat_unread_divider\""),
                          "\(loc) has no chat_unread_divider — that locale shows the raw key")
        }
    }

    /// The open scroll must aim at the DIVIDER. Anchoring `.top` on the bubble
    /// puts the line one row above the viewport, so the reader lands on the
    /// boundary and still cannot see it — the fix would be invisible.
    func testTheOpenScrollAimsAtTheDividerNotTheBubble() throws {
        let views = try source("OSHI/GroupViews.swift")
        XCTAssertFalse(views.contains("scrollTo(unreadId, anchor: .top)"),
                       "the group open scroll aims at the bubble again; the divider ends up offscreen")
        XCTAssertTrue(views.contains("scrollTo(Self.unreadDividerId, anchor: .top)"),
                      "the group open scroll no longer aims at the divider")

        let chat = try source("OSHI/ChatView.swift")
        XCTAssertFalse(chat.contains("scrollTo(id, anchor: .top)"),
                       "the 1:1 landing aims at the bubble again")
        // `.top` on the divider (__UNREAD_LANDING_HOLD_2026_11_16__: a fraction
        // of the viewport moved the line whenever the viewport was resized).
        XCTAssertTrue(chat.contains("scrollTo(ChatView.unreadDividerId, anchor: .top)"),
                      "the 1:1 landing no longer aims at the divider")
    }

    /// The anchor is clamped to the rendered window.
    ///
    /// Unclamped, the behaviour flipped on how much you had missed, and the
    /// wrong way: ~40 unread took you back to the boundary, ~200 unread put the
    /// anchor outside the drawn rows so `scrollTo` hit nothing and the retry
    /// loop fell back to the bottom — losing the boundary in precisely the busy
    /// group the feature was written for.
    func testTheUnreadAnchorIsClampedToTheRenderedWindow() throws {
        let views = try source("OSHI/GroupViews.swift")
        let fn = try slice(views,
                           from: "private func firstUnreadIncomingGroupId() -> String? {",
                           upTo: "?.id.uuidString")
        XCTAssertTrue(fn.contains("messageDisplayLimit"),
                      "the group unread anchor searches the whole history again while the list "
                    + "renders only the newest messageDisplayLimit rows")
        XCTAssertTrue(fn.contains("suffix(messageDisplayLimit)"),
                      "the clamp is not the newest-N window any more")
    }

    // MARK: - helpers

    /// Comments stripped before any search: a guard that its own explanation
    /// satisfies cannot fail.
    private static func strippingComments(_ source: String) -> String {
        // __A_STRING_IS_NOT_A_COMMENT_2026_11_07__ String literals are copied
        // through WITHOUT looking for comment markers inside them.
        //
        // The Kotlin twin of this helper was string-unaware, and `ChatScreen.kt`
        // passes the MIME wildcard (star slash star) to a document picker. The
        // slash-star inside that literal opened a block comment that ran two
        // thousand lines: 120 KB of real code blanked before any guard read it.
        // A guard asserting a pattern is PRESENT then fails loudly, but one
        // asserting a pattern is ABSENT passes VACUOUSLY — it reports the defect
        // gone while the defect sits in the blanked region.
        //
        // No Swift file any guard currently scans carries that sequence in a
        // string (only `CovertHTTP.swift` does, and nothing scans it), so this
        // is closing the hazard rather than fixing a live failure.
        var out = ""
        var i = source.startIndex
        while i < source.endIndex {
            let c = source[i]
            let next = source.index(after: i)
            let n: Character = next < source.endIndex ? source[next] : " "

            if c == "\"" {
                out.append(c)
                i = next
                while i < source.endIndex {
                    let d = source[i]
                    out.append(d)
                    i = source.index(after: i)
                    if d == "\\", i < source.endIndex {
                        out.append(source[i]); i = source.index(after: i); continue
                    }
                    if d == "\"" { break }
                    // An unterminated literal must not eat the file.
                    if d == "\n" { break }
                }
                continue
            }
            if c == "/" && n == "*" {
                i = source.index(after: next)
                while i < source.endIndex {
                    if source[i] == "*" {
                        let after = source.index(after: i)
                        if after < source.endIndex && source[after] == "/" {
                            i = source.index(after: after); break
                        }
                    }
                    if source[i] == "\n" { out.append("\n") }
                    i = source.index(after: i)
                }
                continue
            }
            if c == "/" && n == "/" {
                while i < source.endIndex && source[i] != "\n" { i = source.index(after: i) }
                continue
            }
            out.append(c)
            i = source.index(after: i)
        }
        return out
    }

    private func source(_ relative: String) throws -> String {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()   // OSHITests/
        dir = dir.deletingLastPathComponent()                                   // repo root
        let url = dir.appendingPathComponent(relative)
        guard let text = try? String(contentsOf: url, encoding: .utf8) else {
            XCTFail("\(relative) not readable at \(url.path)")
            throw NSError(domain: "MeshMediaAndUnreadTests", code: 1)
        }
        return Self.strippingComments(text)
    }

    /// Fails loudly rather than returning empty: a slice that silently found
    /// nothing makes every `XCTAssertFalse` on it pass.
    private func slice(_ text: String, from start: String, upTo end: String) throws -> String {
        guard let a = text.range(of: start) else {
            XCTFail("could not find \"\(start)\"")
            throw NSError(domain: "MeshMediaAndUnreadTests", code: 2)
        }
        guard let b = text.range(of: end, range: a.upperBound..<text.endIndex) else {
            XCTFail("could not find \"\(end)\" after \"\(start)\"")
            throw NSError(domain: "MeshMediaAndUnreadTests", code: 3)
        }
        return String(text[a.upperBound..<b.lowerBound])
    }
}
