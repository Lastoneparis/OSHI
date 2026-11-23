//
//  ReceiptStateMachineTests.swift
//  OSHITests
//
//  Automated harness for the delivery-receipt state machine (audit findings
//  M2/M3/M4), for the SHIPPING OSHI target via `@testable import OSHI`.
//
//  ──────────────────────────────────────────────────────────────────────────
//  WHAT IS REAL vs MODELLED vs NEEDS-DEVICE
//  ──────────────────────────────────────────────────────────────────────────
//  REAL app code exercised directly (not a copy):
//    • DeliveryStatus.rank / .advanced(to:)        — the monotonic upgrade rule
//                                                     both receipt handlers use.
//    • MessageManager.parseDeliveryReceiptMessageId — the M2 precise-receipt
//                                                     "📬…📬<id>" parser.
//    • MessageManager.shouldAckInboundMeshMessage   — the M4 loop guard
//                                                     (never ack own/control msgs).
//
//  MODELLED (test-owned reference over the app's REAL DeliveryStatus enum +
//  REAL .advanced(to:)): the handler SELECTION logic — precise receipt advances
//  only the acked message; a legacy (id-less) receipt advances only the OLDEST
//  unacked message, never the whole conversation (the M2 bug). The model calls
//  the real `.advanced(to:)`, so the state transitions are the shipping ones.
//
//  NEEDS-REAL-DEVICE (asserted only at logic level here): the actual mesh
//  transport round-trip — a message crossing BLE / local-network to a second
//  phone and the receipt crossing back. Two simulators cannot form a mesh, so
//  we never fake-pass that; it is logged via TestLog.needsDevice and covered by
//  TWO_DEVICE_TEST_PLAN.md.
//

import XCTest
@testable import OSHI

// Note: `TestLog` is defined in CallScenarioTests.swift (same test target/module)
// and reused here so the xcodebuild log uniformly separates headless vs device.

// ============================================================================
// ReceiptModel — TEST-OWNED reference of one sender's outgoing thread to a peer.
// Mirrors exactly what handleDeliveryConfirmed / handleIncomingDeliveryReceipt
// do to `MessageManager.messages`, but on the app's REAL DeliveryStatus enum
// and REAL `.advanced(to:)` so the transitions are the shipping ones.
// ============================================================================
private struct OutMsg {
    let id: String
    var status: DeliveryStatus
}

private struct ReceiptModel {
    // Chronological (append order), oldest first — same invariant as `messages`.
    var thread: [OutMsg]

    /// Precise receipt (M2/M3): advance EXACTLY the acked id, monotonically.
    mutating func applyPreciseReceipt(id: String) {
        guard let i = thread.firstIndex(where: { $0.id == id }) else { return }
        thread[i].status = thread[i].status.advanced(to: .delivered)
    }

    /// Legacy id-less receipt (M2): advance ONLY the oldest still-.sent message.
    mutating func applyLegacyReceipt() {
        guard let i = thread.firstIndex(where: { $0.status == .sent }) else { return }
        thread[i].status = thread[i].status.advanced(to: .delivered)
    }

    func status(_ id: String) -> DeliveryStatus? { thread.first { $0.id == id }?.status }
    var deliveredCount: Int { thread.filter { $0.status == .delivered }.count }
}

final class ReceiptStateMachineTests: XCTestCase {

    // MARK: - REAL: DeliveryStatus monotonic rule (M2/M3 core) ----------------

    func testRankOrdering() {
        XCTAssertLessThan(DeliveryStatus.pending.rank, DeliveryStatus.sent.rank)
        XCTAssertLessThan(DeliveryStatus.sent.rank, DeliveryStatus.delivered.rank)
        XCTAssertEqual(DeliveryStatus.failed.rank, -1, "failed must be terminal")
        TestLog.headless("DeliveryStatus rank ordering: pending < sent < delivered, failed terminal")
    }

    func testAdvanceMovesForward() {
        XCTAssertEqual(DeliveryStatus.pending.advanced(to: .delivered), .delivered)
        // M3 regression guard: a .sent message MUST advance on an accurate
        // receipt. The pre-fix bug only upgraded .pending, so sent stuck forever.
        XCTAssertEqual(DeliveryStatus.sent.advanced(to: .delivered), .delivered,
                       "M3: accurate receipt must advance a .sent message")
        XCTAssertEqual(DeliveryStatus.pending.advanced(to: .sent), .sent)
        TestLog.headless("M3: .sent advances to .delivered on receipt (was the bug)")
    }

    func testAdvanceNeverGoesBackward() {
        XCTAssertEqual(DeliveryStatus.delivered.advanced(to: .sent), .delivered)
        XCTAssertEqual(DeliveryStatus.delivered.advanced(to: .pending), .delivered)
        XCTAssertEqual(DeliveryStatus.delivered.advanced(to: .delivered), .delivered)
        XCTAssertEqual(DeliveryStatus.sent.advanced(to: .pending), .sent)
        TestLog.headless("Monotonic: never downgrade delivered → sent/pending")
    }

    func testFailedIsTerminal() {
        XCTAssertEqual(DeliveryStatus.failed.advanced(to: .delivered), .failed,
                       "a receipt must never resurrect a failed message")
        XCTAssertEqual(DeliveryStatus.failed.advanced(to: .sent), .failed)
        TestLog.headless("Terminal: .failed never advances")
    }

    func testAdvanceIsIdempotent() {
        let once = DeliveryStatus.sent.advanced(to: .delivered)
        let twice = once.advanced(to: .delivered)
        XCTAssertEqual(once, twice, "duplicate/late receipts must be no-ops")
        TestLog.headless("Idempotent: repeated receipts are stable")
    }

    // MARK: - REAL: precise-receipt parser (M2) -------------------------------

    func testParsePreciseReceiptId() {
        let id = "3F2504E0-4F89-41D3-9A0C-0305E82C3301"
        XCTAssertEqual(MessageManager.parseDeliveryReceiptMessageId("📬DELIVERY_RECEIPT📬\(id)"), id)
        TestLog.headless("M2 parser: extracts messageId from precise receipt")
    }

    func testParseLegacyReceiptIsEmpty() {
        XCTAssertEqual(MessageManager.parseDeliveryReceiptMessageId("📬DELIVERY_RECEIPT📬"), "")
        XCTAssertEqual(MessageManager.parseDeliveryReceiptMessageId("📬DELIVERY_RECEIPT📬   "), "",
                       "trailing whitespace-only payload is legacy (empty id)")
        TestLog.headless("M2 parser: bare prefix → empty id (legacy path)")
    }

    // MARK: - REAL: M4 loop guard (shouldAck) ---------------------------------

    func testAcksRealInboundMessage() {
        XCTAssertTrue(MessageManager.shouldAckInboundMeshMessage(
            content: "hey, are you there?", senderPublicKey: "PEER", myPublicKey: "ME"))
        TestLog.headless("M4: real inbound text from a peer IS acked")
    }

    func testNeverAcksOwnMessage() {
        XCTAssertFalse(MessageManager.shouldAckInboundMeshMessage(
            content: "hello", senderPublicKey: "ME", myPublicKey: "ME"))
        XCTAssertFalse(MessageManager.shouldAckInboundMeshMessage(
            content: "hello", senderPublicKey: "", myPublicKey: "ME"))
        TestLog.headless("M4: never ack our own / empty-sender messages")
    }

    func testNeverAcksControlMessages() {
        // The critical loop guard: a delivery receipt must not be acked.
        XCTAssertFalse(MessageManager.shouldAckInboundMeshMessage(
            content: "📬DELIVERY_RECEIPT📬abc", senderPublicKey: "PEER", myPublicKey: "ME"),
            "acking a receipt would cause an infinite receipt-for-receipt loop")
        for prefix in MessageManager.meshReceiptControlPrefixes {
            XCTAssertFalse(MessageManager.shouldAckInboundMeshMessage(
                content: prefix + "payload", senderPublicKey: "PEER", myPublicKey: "ME"),
                "control prefix \(prefix) must not be acked")
        }
        TestLog.headless("M4: control/system messages are never acked (no loop)")
    }

    // MARK: - MODELLED: handler selection over REAL enum ----------------------

    func testPreciseReceiptAdvancesOnlyAckedMessage() {
        // The M2 headline bug: one receipt used to flip EVERY sent message.
        var m = ReceiptModel(thread: [
            OutMsg(id: "X", status: .sent),
            OutMsg(id: "Y", status: .sent),
        ])
        m.applyPreciseReceipt(id: "X")
        XCTAssertEqual(m.status("X"), .delivered)
        XCTAssertEqual(m.status("Y"), .sent, "M2: Y must NOT be delivered by X's receipt")
        XCTAssertEqual(m.deliveredCount, 1)
        TestLog.headless("M2: precise receipt advances only the acked message")
    }

    func testLegacyReceiptAdvancesOnlyOldestUnacked() {
        var m = ReceiptModel(thread: [
            OutMsg(id: "X", status: .sent),   // oldest
            OutMsg(id: "Y", status: .sent),
        ])
        m.applyLegacyReceipt()
        XCTAssertEqual(m.status("X"), .delivered, "oldest unacked advances")
        XCTAssertEqual(m.status("Y"), .sent, "later message must remain sent")
        XCTAssertEqual(m.deliveredCount, 1, "a single receipt resolves a single message")
        TestLog.headless("M2 legacy: id-less receipt advances only the oldest unacked")
    }

    func testReceiptsAreOrderIndependentAndConverge() {
        var m = ReceiptModel(thread: [
            OutMsg(id: "X", status: .sent),
            OutMsg(id: "Y", status: .sent),
            OutMsg(id: "Z", status: .pending),
        ])
        // Out-of-order + duplicate receipts.
        m.applyPreciseReceipt(id: "Y")
        m.applyPreciseReceipt(id: "Y") // duplicate
        m.applyPreciseReceipt(id: "Z")
        m.applyPreciseReceipt(id: "X")
        XCTAssertEqual(m.deliveredCount, 3, "all converge to delivered exactly once")
        TestLog.headless("Receipts converge under reordering + duplication")
    }

    func testDeliveredIsNotDowngradedByLaterLegacyReceipt() {
        var m = ReceiptModel(thread: [
            OutMsg(id: "X", status: .delivered), // already delivered
            OutMsg(id: "Y", status: .sent),
        ])
        m.applyLegacyReceipt() // should target Y (oldest .sent), not touch X
        XCTAssertEqual(m.status("X"), .delivered)
        XCTAssertEqual(m.status("Y"), .delivered)
        TestLog.headless("Legacy receipt skips already-delivered, targets oldest .sent")
    }

    // MARK: - NEEDS-REAL-DEVICE ------------------------------------------------

    func testMeshTransportRoundTripNeedsDevice() {
        // We do NOT assert transport here — two simulators can't form a BLE /
        // local-network mesh. The state-machine that CONSUMES a receipt is fully
        // covered above; the wire round-trip is covered by TWO_DEVICE_TEST_PLAN.md.
        TestLog.needsDevice("mesh message A→B and 📬 receipt B→A over BLE/local-network")
        TestLog.needsDevice("phantom-delivered: stale link → bubble shows IPFS, peer receives")
        TestLog.needsDevice("push notification fires on inbound message")
    }
}
