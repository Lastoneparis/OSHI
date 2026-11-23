//
//  InviteLinkTests.swift
//  OSHITests
//
//  __FIRST_CONTACT_REMOTE_2026_10_10__ The invite link is the only thing a lost
//  first-time user has to get right, and it is handed to someone else. If the
//  key is corrupted in transit the failure is silent and looks like "OSHI is
//  broken", so the encoding is pinned here.
//

import XCTest
@testable import OSHI

final class InviteLinkTests: XCTestCase {

    /// A real base64 key shape: contains `+`, `/` and `=`, which is the whole
    /// reason this needs escaping.
    private let key = "abc+def/ghi=="

    func testBase64KeysSurviveTheQueryString() throws {
        let url = try XCTUnwrap(InviteLink.url(forMyPublicKey: key))
        let s = url.absoluteString

        // `+` in a query string is read as a SPACE by most receivers. Left
        // unescaped it silently turns into " " and the key no longer matches
        // any identity — a dead invite that reports nothing.
        XCTAssertFalse(s.contains("+"), "a raw + would arrive as a space")
        XCTAssertTrue(s.contains("%2B"), "+ must be percent-encoded")
        XCTAssertTrue(s.contains("%2F"), "/ must be percent-encoded")
        XCTAssertTrue(s.contains("%3D"), "= must be percent-encoded")

        // And it must decode back to exactly the key we put in.
        let comps = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        let value = try XCTUnwrap(comps.queryItems?.first(where: { $0.name == "key" })?.value)
        XCTAssertEqual(value, key, "the recipient must recover the key byte for byte")
    }

    func testItIsAnHttpsUniversalLinkNotACustomScheme() throws {
        let url = try XCTUnwrap(InviteLink.url(forMyPublicKey: key))
        // A first invite usually goes to someone who does NOT have OSHI yet.
        // `oshi://` does nothing at all on such a phone; https at least lands
        // somewhere that can explain what to install.
        XCTAssertEqual(url.scheme, "https")
        XCTAssertEqual(url.host, InviteLink.host)
        XCTAssertEqual(url.path, "/add")
    }

    /// __THE_INVITE_LINK_POINTED_AT_THE_UNVERIFIABLE_HOST_2026_11_07__
    ///
    /// This test used to accept EITHER domain, which is how the bug shipped:
    /// being in the entitlement is necessary and not sufficient. `.org` is in
    /// the entitlement and still cannot be verified, because it 301-redirects
    /// `/.well-known/apple-app-site-association` (and `assetlinks.json`) to
    /// `.com` — and neither Apple's CDN nor Android App Links follows a
    /// redirect when fetching those. A 301 is a verification failure, so the
    /// OS never binds the domain and the link opens a browser: Anatole's
    /// "the invite link does not work when it is sent in a conversation".
    ///
    /// Pinned to the host that actually serves both files with HTTP 200.
    /// If `.org` is ever fixed server-side this can widen again — but it must
    /// be widened deliberately, by someone who has re-checked the two URLs,
    /// not by a test that never cared which one shipped.
    func testHostIsTheDomainThatActuallyVerifies() {
        XCTAssertEqual(InviteLink.host, "oshi-messenger.com",
                       "the invite host must be one that serves its association file "
                     + "directly; .org 301-redirects it and therefore never verifies")
    }

    /// Receiving is deliberately WIDER than sending: links already sent sit in
    /// people's chat histories and must keep working if `.org` is ever fixed.
    func testTheReceiverStillAcceptsTheOldHost() throws {
        let handler = try String(
            contentsOf: URL(fileURLWithPath: #filePath)
                .deletingLastPathComponent().deletingLastPathComponent()
                .appendingPathComponent("OSHI/SharedContentHandler.swift"),
            encoding: .utf8)
        XCTAssertTrue(handler.contains("oshi-messenger.org"),
                      "the receiver stopped accepting .org — every invite already sent breaks")
    }

    func testAnEmptyKeyProducesNoLinkRatherThanABrokenOne() {
        XCTAssertNil(InviteLink.url(forMyPublicKey: ""))
        XCTAssertNil(InviteLink.url(forMyPublicKey: "   "))
        XCTAssertNil(InviteLink.message(forMyPublicKey: "", senderName: "Hugo"))
        XCTAssertTrue(InviteLink.shareItems(forMyPublicKey: "", senderName: "Hugo").isEmpty,
                      "better to offer nothing than to send a link with no key in it")
    }

    func testTheMessageCarriesTheLinkAndSurvivesAMissingNickname() throws {
        let withName = try XCTUnwrap(InviteLink.message(forMyPublicKey: key, senderName: "Hugo"))
        XCTAssertTrue(withName.contains("Hugo"))
        XCTAssertTrue(withName.contains("https://\(InviteLink.host)/add?key="))

        // A brand-new user may not have set a nickname yet; the invite must not
        // read as if a name were missing.
        for blank in [nil, "", "   "] as [String?] {
            let anon = try XCTUnwrap(InviteLink.message(forMyPublicKey: key, senderName: blank))
            XCTAssertTrue(anon.contains("https://\(InviteLink.host)/add?key="))
            XCTAssertFalse(anon.hasPrefix(" "), "no leading gap where the name would be")
        }
    }

    func testTheShareSheetGetsTextOnlySoTheExplanationIsNotDropped() throws {
        let items = InviteLink.shareItems(forMyPublicKey: key, senderName: "Hugo")
        // Handing the sheet a separate URL item makes Mail and Notes attach a
        // link preview and drop the sentence — and the sentence is the part
        // that tells the recipient what to do.
        XCTAssertEqual(items.count, 1)
        XCTAssertTrue(items.first is String)
    }
}
