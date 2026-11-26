//
//  GroupFanoutBatchingTests.swift
//  OSHITests
//
//  __GROUP_FANOUT_2026_11_24__ A group message to more than 60 members failed from iOS: the whole
//  group went out as ONE relay batch, the relay's per-sender bucket holds 60 tokens (one per
//  member), and the batch was refused with 429 on every attempt — reproduced on a local copy of
//  the production relay (60 → 200, 61 → 429 ×4). These tests pin the three parts of the fix:
//  batches of ≤ 50, the 429 wait (Retry-After), and no legacy copy because of a 429.
//

import XCTest
@testable import OSHI

final class GroupFanoutBatchingTests: XCTestCase {

    // MARK: batching

    func testRangesNeverExceedFiftyAndCoverEveryMember() {
        for n in [1, 49, 50, 51, 60, 61, 200, 1000] {
            let r = GroupFanoutBatcher.ranges(count: n)
            XCTAssertTrue(r.allSatisfy { $0.count <= 50 }, "n=\(n)")
            XCTAssertEqual(r.reduce(0) { $0 + $1.count }, n)
            XCTAssertEqual(r.first?.lowerBound, 0)
            for (a, b) in zip(r, r.dropFirst()) { XCTAssertEqual(a.upperBound, b.lowerBound) }
        }
        XCTAssertEqual(GroupFanoutBatcher.ranges(count: 200).count, 4)
        XCTAssertEqual(GroupFanoutBatcher.ranges(count: 1000).count, 20)
        XCTAssertTrue(GroupFanoutBatcher.ranges(count: 0).isEmpty)
    }

    func testFirstBatchLeavesRoomForTheSelfCopy() {
        let r = GroupFanoutBatcher.ranges(count: 100, firstReserve: 1)
        XCTAssertEqual(r.map(\.count), [49, 50, 1])
    }

    // MARK: 429 wait

    private func run(_ answers: [(Int, String?)]) async throws -> (last: Int, calls: Int, sleeps: [TimeInterval]) {
        var i = 0
        var sleeps: [TimeInterval] = []
        let last = try await V2RateLimitBackoff.retrying({ () async throws -> (Int, String?) in
            defer { i += 1 }
            return answers[min(i, answers.count - 1)]
        }, status: { $0.0 }, retryAfter: { $0.1 }, sleep: { sleeps.append($0) })
        return (last.0, i, sleeps)
    }

    func testHonoursRetryAfter() async throws {
        let r = try await run([(429, "3"), (429, "1"), (200, nil)])
        XCTAssertEqual(r.last, 200)
        XCTAssertEqual(r.sleeps, [3, 1])
    }

    func testBacksOffWithoutRetryAfter() async throws {
        // relays before ServerPatches/group_fanout_2026_11_24 answer a bare 429
        let r = try await run([(429, nil), (429, nil), (429, nil), (200, nil)])
        XCTAssertEqual(r.last, 200)
        XCTAssertEqual(r.sleeps, [1, 2, 4])
    }

    func testGivesUpAfterTheBudget() async throws {
        let r = try await run([(429, "30")])
        XCTAssertEqual(r.last, 429)
        XCTAssertEqual(r.sleeps, [30, 30, 30, 30])
        XCTAssertEqual(V2RateLimitBackoff.nextDelay(retryAfter: "999", rateLimitedSoFar: 0, waitedSoFar: 0), 30)
        XCTAssertEqual(V2RateLimitBackoff.nextDelay(retryAfter: "0", rateLimitedSoFar: 0, waitedSoFar: 0), 1)
        XCTAssertNil(V2RateLimitBackoff.retryAfterSeconds("Wed, 21 Oct 2015 07:28:00 GMT"))
    }

    func testOtherStatusesAreReturnedAtOnce() async throws {
        for s in [200, 400, 401, 409, 413, 500] {
            let r = try await run([(s, "1"), (200, nil)])
            XCTAssertEqual(r.last, s); XCTAssertEqual(r.calls, 1); XCTAssertTrue(r.sleeps.isEmpty)
        }
    }

    func testRateLimitedErrorIsRecognised() {
        XCTAssertTrue(V2RateLimitBackoff.isRateLimited(V2.ClientError.http(status: 429, reason: nil)))
        XCTAssertFalse(V2RateLimitBackoff.isRateLimited(V2.ClientError.http(status: 413, reason: nil)))
        XCTAssertFalse(V2RateLimitBackoff.isRateLimited(URLError(.timedOut)))
    }

    // MARK: no legacy copy because of a 429

    func testRateLimitedMembersAreNeverLegacyTargets() {
        let a = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        let b = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8="
        XCTAssertEqual(GroupLegacyCompat.withoutRateLimited([a, b], rateLimited: [b]), [a])
        XCTAssertEqual(GroupLegacyCompat.withoutRateLimited([a, b], rateLimited: []), [a, b])
    }

    func testLedgerIsTakenOnce() {
        let id = UUID().uuidString
        GroupRateLimitLedger.shared.record(messageId: id, members: ["x", "y"])
        XCTAssertEqual(GroupRateLimitLedger.shared.take(messageId: id), ["x", "y"])
        XCTAssertEqual(GroupRateLimitLedger.shared.take(messageId: id), [])
    }
}
