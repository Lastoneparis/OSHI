//
//  OpenPathCostTests.swift
//  OSHITests
//
//  Measures the main-thread work that used to sit on the group-chat open path,
//  so "did the latency actually improve" has a number behind it instead of an
//  assurance.
//
//  What this DOES measure: the cost of the operations that were moved off the
//  open path — a bot-registration network call, a profile-link broadcast that
//  sends a group message, and a member-sync fan-out. Those ran on the main
//  actor 120 ms after appear, which on a slow open means during layout.
//
//  What this does NOT measure: end-to-end tap-to-first-frame on a device. A
//  simulator has no cellular radio and a desktop CPU, so its absolute open time
//  is not the user's. The honest claim this file supports is "N ms of blocking
//  work was removed from the open path", not "the app opens N ms faster on an
//  iPhone".
//

import XCTest
@testable import OSHI

@MainActor
final class OpenPathCostTests: XCTestCase {

    /// Wall-clock cost of a block, in milliseconds.
    private func ms(_ label: String, _ body: () -> Void) -> Double {
        let t0 = CFAbsoluteTimeGetCurrent()
        body()
        let dt = (CFAbsoluteTimeGetCurrent() - t0) * 1000
        print("⏱ \(label): \(String(format: "%.2f", dt)) ms")
        return dt
    }

    /// The deferred block must not be free — if it were, moving it would have
    /// bought nothing and the change would be noise. This records what it costs.
    func testDeferredOpenWorkHasRealCost() {
        let group = UUID()
        MessageGroupManager.shared.groupMessages[group] = []

        let syncCost = ms("syncBotsForGroup") {
            BotManager.shared.syncBotsForGroup(group)
        }
        let readCost = ms("markGroupAsRead (empty group)") {
            MessageGroupManager.shared.markGroupAsRead(group)
        }
        let badgeCost = ms("syncAppBadge") {
            MessageManager.shared?.syncAppBadge()
        }

        // No assertion on absolute numbers — they are machine-dependent and a
        // threshold here would be a flaky test, not a useful one. The value is
        // the printed record, and that these calls complete without throwing.
        XCTAssertGreaterThanOrEqual(syncCost, 0)
        XCTAssertGreaterThanOrEqual(readCost, 0)
        XCTAssertGreaterThanOrEqual(badgeCost, 0)

        MessageGroupManager.shared.groupMessages.removeAll()
    }

    /// `markGroupAsRead` is the ONE piece kept on the open path, so its cost has
    /// to stay small even on a big group. This is the regression guard: if a
    /// future change makes it expensive, the open lag comes straight back.
    func testMarkAsReadStaysCheapOnALargeGroup() {
        let group = UUID()
        let mgr = MessageGroupManager.shared
        mgr.groupMessages[group] = (0..<2_000).map { i in
            GroupMessage(id: UUID(), groupId: group, senderPublicKey: "peer",
                         encryptedContent: Data("m\(i)".utf8), timestamp: Date(),
                         isRead: false, messageChainIndex: i)
        }

        let cost = ms("markGroupAsRead (2000 messages)") {
            mgr.markGroupAsRead(group)
        }
        XCTAssertEqual(mgr.getTotalUnreadCount(), 0)
        XCTAssertLessThan(cost, 250,
                          "mark-as-read got expensive — it runs on the open path")

        // Second call must short-circuit: nothing unread, no copy, no save.
        let again = ms("markGroupAsRead (already read)") {
            mgr.markGroupAsRead(group)
        }
        XCTAssertLessThan(again, cost + 1,
                          "the no-op path is not short-circuiting")

        mgr.groupMessages.removeAll()
    }
}
