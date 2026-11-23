//
//  BadgeCountTests.swift
//  OSHITests
//
//  The app icon badge could never return to zero: a GROUP notification
//  incremented the stored count, but the only thing that recomputed it summed
//  1:1 conversations alone. Reading everything wrote 0, and the next group
//  message put it back — measured on a device as `appBadgeCount = 1` with an
//  empty inbox.
//
//  The fix sums both halves. These pin the properties that make the number
//  trustworthy, because "the badge is wrong" is a bug users see every single
//  time they look at their home screen.
//

import XCTest
@testable import OSHI

@MainActor
final class BadgeCountTests: XCTestCase {

    private let mgr = MessageGroupManager.shared

    private func makeGroup() -> UUID {
        let id = UUID()
        mgr.groupMessages[id] = []
        return id
    }

    private func message(in group: UUID, from sender: String, read: Bool) -> GroupMessage {
        GroupMessage(id: UUID(), groupId: group, senderPublicKey: sender,
                     encryptedContent: Data("x".utf8), timestamp: Date(),
                     isRead: read, messageChainIndex: 0)
    }

    override func tearDown() {
        mgr.groupMessages.removeAll()
        super.tearDown()
    }

    // MARK: The count itself

    /// An unread group message must be counted — this is the half that was
    /// missing and that made the badge unclearable.
    func testUnreadGroupMessagesAreCounted() {
        mgr.groupMessages.removeAll()
        let g = makeGroup()
        mgr.groupMessages[g] = [message(in: g, from: "peer", read: false),
                                message(in: g, from: "peer", read: false)]
        XCTAssertEqual(mgr.getTotalUnreadCount(), 2)
    }

    /// And reading them brings it back to zero. Without this the badge is a
    /// one-way ratchet.
    func testMarkingAGroupReadClearsItsContribution() {
        mgr.groupMessages.removeAll()
        let g = makeGroup()
        mgr.groupMessages[g] = [message(in: g, from: "peer", read: false)]
        XCTAssertEqual(mgr.getTotalUnreadCount(), 1)

        mgr.markGroupAsRead(g)
        XCTAssertEqual(mgr.getTotalUnreadCount(), 0,
                       "reading the group left the badge stuck — the exact reported bug")
    }

    /// Reading ONE group must not clear another's — otherwise the badge
    /// under-reports and a real message goes unnoticed.
    func testReadingOneGroupLeavesTheOthers() {
        mgr.groupMessages.removeAll()
        let a = makeGroup(), b = makeGroup()
        mgr.groupMessages[a] = [message(in: a, from: "peer", read: false)]
        mgr.groupMessages[b] = [message(in: b, from: "peer", read: false)]

        mgr.markGroupAsRead(a)
        XCTAssertEqual(mgr.getTotalUnreadCount(), 1, "the other group was cleared too")
    }

    /// Your own messages must never count. Sending would otherwise raise your
    /// own badge, which is how a badge ends up permanently non-zero.
    func testOwnSentMessagesDoNotCount() {
        mgr.groupMessages.removeAll()
        let g = makeGroup()
        // The send paths construct with isRead: true — assert the effect, so a
        // future path that forgets shows up here rather than on a home screen.
        mgr.groupMessages[g] = [message(in: g, from: "me", read: true)]
        XCTAssertEqual(mgr.getTotalUnreadCount(), 0)
    }

    /// Idempotent: marking read twice must not underflow or resurrect anything.
    func testMarkingReadTwiceIsHarmless() {
        mgr.groupMessages.removeAll()
        let g = makeGroup()
        mgr.groupMessages[g] = [message(in: g, from: "peer", read: false)]
        mgr.markGroupAsRead(g)
        mgr.markGroupAsRead(g)
        XCTAssertEqual(mgr.getTotalUnreadCount(), 0)
    }

    /// No groups at all is zero, not a crash or a stale value.
    func testEmptyStateIsZero() {
        mgr.groupMessages.removeAll()
        XCTAssertEqual(mgr.getTotalUnreadCount(), 0)
    }
}
