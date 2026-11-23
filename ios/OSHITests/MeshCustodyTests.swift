//
//  MeshCustodyTests.swift
//  OSHITests
//
//  __MESH_CUSTODY_2026_10_10__ The rules that decide whether a node keeps
//  somebody else's message, and for how long. Pure functions on purpose — the
//  radio and the disk are not needed to pin the policy, and the policy is the
//  part that can quietly go wrong.
//

import XCTest
@testable import OSHI

final class MeshCustodyTests: XCTestCase {

    // MARK: - TTL

    func testAMessageIsCarriedForSixHoursAndNotLonger() {
        let now = Date()
        XCTAssertFalse(MeshCustodyStore.isExpired(takenAt: now, now: now),
                       "just taken")
        XCTAssertFalse(MeshCustodyStore.isExpired(takenAt: now.addingTimeInterval(-5 * 3600), now: now),
                       "five hours in, still worth forwarding")
        XCTAssertTrue(MeshCustodyStore.isExpired(takenAt: now.addingTimeInterval(-MeshCustodyStore.maxAge), now: now),
                      "exactly at the limit counts as expired")
        XCTAssertTrue(MeshCustodyStore.isExpired(takenAt: now.addingTimeInterval(-24 * 3600), now: now),
                      "a phone is not a mail server")
    }

    func testTheWindowIsLongEnoughForTheCaseItExistsFor() {
        // The dead MeshRelayManager used 600 s, which does not survive a
        // courier walking out of range and coming back — the whole scenario.
        XCTAssertGreaterThan(MeshCustodyStore.maxAge, 600,
                             "ten minutes was the old dead implementation's window and it was too short")
    }

    // MARK: - Bounds

    func testRoomIsBoundedByBothCountAndBytes() {
        // Count is the binding limit.
        XCTAssertFalse(MeshCustodyStore.hasRoom(count: MeshCustodyStore.maxCount, bytes: 0, incoming: 10),
                       "full by count, even with no bytes used")
        // Bytes are the binding limit.
        XCTAssertFalse(MeshCustodyStore.hasRoom(count: 1, bytes: MeshCustodyStore.maxBytes, incoming: 1),
                       "full by bytes, even with one record")
        // Room on both.
        XCTAssertTrue(MeshCustodyStore.hasRoom(count: 1, bytes: 1_000, incoming: 1_000))
    }

    func testOneMessageBiggerThanTheWholeBudgetIsRefusedRatherThanEvictingEverything() {
        // Otherwise a single oversized packet would flush every other message
        // out of custody and then still not fit.
        XCTAssertFalse(MeshCustodyStore.hasRoom(count: 0, bytes: 0,
                                                incoming: MeshCustodyStore.maxBytes + 1),
                       "refuse it; do not clear the store for something that cannot fit")
        XCTAssertTrue(MeshCustodyStore.hasRoom(count: 0, bytes: 0,
                                               incoming: MeshCustodyStore.maxBytes),
                      "exactly the budget still fits an empty store")
    }

    // MARK: - The hop-budget invariant

    func testAHeldMessageKeepsItsHopCountAndPath() throws {
        // THE invariant. A deferred message must not be re-originated: that
        // would hand it a fresh hop budget and let it outlive the ceiling,
        // which is the class of bug the 2026-09-08 audit fixed by making the
        // hop ceiling ours rather than the packet's.
        let msg = SecureMessage(
            id: "held-1",
            senderAddress: "a",
            recipientAddress: "b",
            encryptedContent: EncryptedMessage(ciphertext: "x", signature: "s",
                                               senderPublicKey: "pk", timestamp: Date()),
            timestamp: Date(),
            isRead: false,
            deliveryStatus: .pending
        )
        let relay = RelayMessage(message: msg, hopCount: 4, maxHops: 500,
                                 seenBy: ["origin", "hopA", "me"])

        let encoded = try JSONEncoder().encode(relay)
        let decoded = try JSONDecoder().decode(RelayMessage.self, from: encoded)

        XCTAssertEqual(decoded.hopCount, 4, "custody must not reset the hop count")
        XCTAssertEqual(decoded.seenBy, ["origin", "hopA", "me"],
                       "the path must survive so a held message is never sent back down it")
        XCTAssertEqual(decoded.message.id, "held-1")
    }

    // MARK: - Kill switch

    func testCustodyIsOnByDefaultAndCanBeTurnedOff() {
        let key = MeshCustodyStore.defaultsKey
        let original = UserDefaults.standard.object(forKey: key)
        defer {
            if let original { UserDefaults.standard.set(original, forKey: key) }
            else { UserDefaults.standard.removeObject(forKey: key) }
        }

        UserDefaults.standard.removeObject(forKey: key)
        XCTAssertTrue(MeshCustodyStore.enabled,
                      "relaying for other people is what the mesh is; absent means on")

        UserDefaults.standard.set(false, forKey: key)
        XCTAssertFalse(MeshCustodyStore.enabled,
                       "the kill switch must restore forward-now-or-drop exactly")
    }
}
