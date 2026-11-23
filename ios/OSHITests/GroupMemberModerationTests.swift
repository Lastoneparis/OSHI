//
//  GroupMemberModerationTests.swift
//  OSHITests
//
//  __MEMBER_COULD_ONLY_BE_REPORTED_AS_A_ROOM_2026_11_06__
//
//  What the owner's abuse reports of 2026-11-06 said, read off the server
//  (`/var/oshi/reports/reports-2026-11-06.jsonl`): four reports from ONE
//  reporter in 33 minutes, every one `"subjectType":"group"`, three of them
//  carrying a PERSON'S name in the free-text field — "Danielle Bespoke",
//  "Sarah", "Detee now". Two were the same group id 14 seconds apart, then a
//  `harassment` report on that same group 25 seconds later with no details.
//
//  That is one person trying four times to flag individuals through the only
//  subject the product offered.
//
//  iOS was the worse of the two platforms. Android at least had a member menu,
//  missing Block and Report; on iPhone the member rows in `GroupInfoSheet` were
//  an inert `HStack` with no gesture at all. A member of a group could not be
//  blocked, reported, or acted on from anywhere in the app.
//
//  The enforcement was never missing: `GroupMessaging.receiveGroupMessage`
//  has dropped blocked members' messages since __BLOCKED_MEMBER_DROP_2026_09_24__,
//  an owner decision. Enforced but unreachable is not shipped — and from the
//  user's side it is indistinguishable from absent.
//
//  Reads source with comments STRIPPED. Learned the hard way twice today: a
//  guard that searches for `xmark` trips on the comment explaining why the
//  `xmark` was removed.
//

import XCTest
@testable import OSHI

final class GroupMemberModerationTests: XCTestCase {

    // MARK: - The row is a control now

    func testTheMemberRowOffersBlockAndReport() throws {
        let menu = try Self.membersSection()
        XCTAssertTrue(menu.contains("group.block_member"),
                      "no Block on the member row — a member spammed by one person has no action against them")
        XCTAssertTrue(menu.contains("group.unblock_member"),
                      "no Unblock — a block with no way back is a trap")
        XCTAssertTrue(menu.contains("group.report_member"),
                      "no Report — the only reachable report is the whole room again")
    }

    func testTheRowIsATapMenuNotAHiddenLongPress() throws {
        let menu = try Self.membersSection()
        XCTAssertTrue(menu.contains("Menu {"),
                      "the member row is inert again, or hides its actions behind a gesture")
        XCTAssertFalse(menu.contains(".contextMenu"),
                       "a long press nobody is told about is the same as no action; this row had no affordance at all for months")
        XCTAssertTrue(menu.contains("ellipsis.circle"),
                      "a row that acts must look like it acts")
    }

    // MARK: - The subject is the person, not the room

    func testAMemberIsReportedAsAContact() throws {
        let section = try Self.membersSection()
        XCTAssertTrue(section.contains("subject: .contact"),
                      "the member report must name a CONTACT: the point is that moderation receives a public key it can act on, not a group id")
        XCTAssertTrue(section.contains("subjectId: member.publicKey"))

        // And the group report survives: this adds a subject, it does not
        // replace one. A room really can be the problem.
        let source = try Self.groupViews()
        XCTAssertTrue(source.contains("subject: .group"),
                      "the group report is gone — some reports really are about the room")
    }

    func testBlockingFromTheRowGoesThroughTheOwnerOfBlocking() throws {
        let section = try Self.membersSection()
        XCTAssertTrue(section.contains("blockedManager.blockContact("),
                      "blocking must stay owned by BlockedContactsManager, as the report sheet's own comment requires")
        XCTAssertTrue(section.contains("blockedManager.unblockContact("))
        XCTAssertTrue(section.contains("blockedManager.isBlocked(member.publicKey)"),
                      "the menu must show the state it will change")
    }

    // MARK: - The premise: blocking a member really does silence them

    func testABlockedMembersGroupMessagesAreStillDroppedAtReceive() throws {
        // If this gate goes, the Block entry added above becomes a button that
        // reports success and changes nothing the user can see.
        let messaging = try Self.repoFile("OSHI/GroupMessaging.swift")
        XCTAssertTrue(messaging.contains("BlockedContactsManager.shared.isBlocked(message.senderPublicKey)"),
                      "the blocked-member drop is gone — blocking a member no longer silences them in a group")
        XCTAssertTrue(messaging.contains("message.senderPublicKey != \"SYSTEM\""),
                      "join/leave rows are not a member's words and must survive the drop")
    }

    // MARK: - Plumbing that would fail silently

    func testGroupMemberCanBeASheetSubject() {
        // `.sheet(item:)` needs Identifiable. Without it this would not compile,
        // so the test is really pinning that `id` stays the public key — a
        // different id would present the wrong member's report sheet.
        let member = GroupMember(publicKey: "KEY-A", alias: "Sarah", joinedAt: Date(), isAdmin: false)
        XCTAssertEqual(member.id, "KEY-A")
    }

    func testTheNewStringsAreTranslatedOrAtLeastPresentEverywhere() throws {
        // A missing key renders as the key itself — "group.block_member" on
        // screen, in a moderation menu. English in every locale beats that.
        let locales = try Self.localeDirectories()
        XCTAssertGreaterThan(locales.count, 30, "found only \(locales.count) locales")
        for url in locales {
            let strings = try String(contentsOf: url.appendingPathComponent("Localizable.strings"), encoding: .utf8)
            for key in ["group.block_member", "group.unblock_member", "group.report_member"] {
                XCTAssertTrue(strings.contains("\"\(key)\""),
                              "\(url.lastPathComponent) is missing \(key)")
            }
        }
    }

    // MARK: - Helpers

    /// The `membersSection` of `GroupInfoSheet`, comments stripped.
    private static func membersSection() throws -> String {
        let source = try groupViews()
        // NOT "// MARK: - Share Button": this reader strips comments, so a
        // comment anchor is never there and `slice` skipped — and a skipped
        // guard reads as a green test. The first run of this file "passed" with
        // 4 of 7 skipped for exactly that reason.
        return try slice(source, from: "private var membersSection: some View {", upTo: "private var shareButton: some View {")
    }

    private static func groupViews() throws -> String {
        try repoFile("OSHI/GroupViews.swift")
    }

    private static func localeDirectories() throws -> [URL] {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("OSHI")
            if FileManager.default.fileExists(atPath: candidate.appendingPathComponent("en.lproj").path) {
                let all = try FileManager.default.contentsOfDirectory(at: candidate, includingPropertiesForKeys: nil)
                return all.filter { $0.pathExtension == "lproj" }.sorted { $0.path < $1.path }
            }
            dir = dir.deletingLastPathComponent()
        }
        throw XCTSkip("no .lproj directories found from \(#filePath)")
    }

    private static func repoFile(_ relative: String) throws -> String {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent(relative)
            if FileManager.default.fileExists(atPath: candidate.path) {
                return MailCloseAffordanceTests.strippingComments(
                    try String(contentsOf: candidate, encoding: .utf8))
            }
            dir = dir.deletingLastPathComponent()
        }
        throw XCTSkip("\(relative) not found from \(#filePath)")
    }

    /// Fails rather than skips when an anchor is missing. A skip here is
    /// indistinguishable from a pass in the summary line, and these guards exist
    /// to be read by someone scanning for red.
    private static func slice(_ source: String, from start: String, upTo end: String) throws -> String {
        guard let a = source.range(of: start) else {
            XCTFail("anchor not found: \(start) — the guard cannot see what it guards")
            throw SliceError.anchorMissing(start)
        }
        guard let b = source.range(of: end, range: a.upperBound..<source.endIndex) else {
            XCTFail("end anchor not found after \(start): \(end)")
            throw SliceError.anchorMissing(end)
        }
        return String(source[a.upperBound..<b.lowerBound])
    }

    private enum SliceError: Error { case anchorMissing(String) }
}
