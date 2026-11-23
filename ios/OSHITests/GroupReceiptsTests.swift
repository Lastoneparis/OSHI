import XCTest
@testable import OSHI

/// __GROUP_RECEIPTS_2026_11_02__ (N8) who received and who read one group
/// message.
///
/// The wire format and the ordering rules are the parts worth pinning: the
/// first is read by a remote peer and the second is what the sheet shows, and
/// both are easy to change by accident later.
final class GroupReceiptsTests: XCTestCase {

    private let g = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!

    override func setUp() {
        super.setUp()
        GroupReceipts.Store.shared.resetForTesting()
    }

    // MARK: Wire

    func testRoundTrip() throws {
        let at = Date(timeIntervalSince1970: 1_790_000_000)
        let wire = try XCTUnwrap(GroupReceipts.encode(groupId: g, msgId: "ABC-123", kind: .read, at: at))
        XCTAssertTrue(wire.hasPrefix(GroupReceipts.prefix))
        let parsed = try XCTUnwrap(GroupReceipts.parse(wire))
        XCTAssertEqual(parsed.groupId, g)
        XCTAssertEqual(parsed.msgId, "ABC-123")
        XCTAssertEqual(parsed.kind, .read)
        XCTAssertEqual(parsed.at.timeIntervalSince1970, 1_790_000_000, accuracy: 1)
    }

    /// The sentinel must be registered as a CONTROL payload. If it is not, a
    /// receipt renders in the thread as a line of JSON — the exact failure the
    /// catalog exists to prevent.
    func testSentinelIsInTheControlCatalog() {
        XCTAssertTrue(OSHIControlPayload.catalog.contains { $0.prefix == GroupReceipts.prefix },
                      "📑GROUP_RECEIPT📑 must be in OSHIControlPayload.catalog or it will render as a message")
    }

    func testRejectsForeignAndMalformedPayloads() {
        XCTAssertNil(GroupReceipts.parse("hello"))
        XCTAssertNil(GroupReceipts.parse("📖READ_RECEIPT📖"))
        XCTAssertNil(GroupReceipts.parse(GroupReceipts.prefix + "not json"))
        // No message id, so nothing to attribute it to.
        XCTAssertNil(GroupReceipts.parse(GroupReceipts.prefix + #"{"g":"\#(g.uuidString)","k":"r"}"#))
        // Unknown kind: a future sender must not silently count as "read".
        XCTAssertNil(GroupReceipts.parse(GroupReceipts.prefix + #"{"g":"\#(g.uuidString)","m":"x","k":"zz"}"#))
    }

    /// The id is a remote-supplied dictionary key, so an absurd one is refused.
    func testRejectsOverlongMessageId() {
        let huge = String(repeating: "A", count: 500)
        XCTAssertNil(GroupReceipts.parse(GroupReceipts.prefix + #"{"g":"\#(g.uuidString)","m":"\#(huge)","k":"d"}"#))
    }

    // MARK: Store

    /// Reading implies receiving. A read receipt can arrive without its
    /// delivery twin — the delivery one can be lost — and "read but never
    /// delivered" would be nonsense on screen.
    func testReadImpliesReceived() {
        let t = Date(timeIntervalSince1970: 1_790_000_100)
        GroupReceipts.Store.shared.record(groupId: g, msgId: "m1", member: "KEY_A", kind: .read, at: t)
        let st = GroupReceipts.Store.shared.states(groupId: g, msgId: "m1")["KEY_A"]
        XCTAssertEqual(st?.readAt, t)
        XCTAssertEqual(st?.receivedAt, t)
    }

    /// A duplicate from a retry must not make delivery look more recent.
    func testStampNeverMovesForward() {
        let early = Date(timeIntervalSince1970: 1_790_000_000)
        let late = Date(timeIntervalSince1970: 1_790_009_999)
        let s = GroupReceipts.Store.shared
        s.record(groupId: g, msgId: "m2", member: "KEY_A", kind: .received, at: early)
        s.record(groupId: g, msgId: "m2", member: "KEY_A", kind: .received, at: late)
        XCTAssertEqual(s.states(groupId: g, msgId: "m2")["KEY_A"]?.receivedAt, early)
    }

    func testStateIsPerMessageNotPerConversation() {
        let s = GroupReceipts.Store.shared
        s.record(groupId: g, msgId: "m1", member: "KEY_A", kind: .read, at: Date())
        XCTAssertTrue(s.states(groupId: g, msgId: "m2").isEmpty,
                      "a receipt for one message must not answer for another")
    }

    // MARK: Rows

    /// A member nobody has heard from MUST appear. Omitting them would read as
    /// everyone having received the message, which is the opposite of true.
    func testRowsIncludeMembersWithNoReceiptAndExcludeTheSender() {
        let s = GroupReceipts.Store.shared
        s.record(groupId: g, msgId: "m1", member: "READER", kind: .read, at: Date())
        s.record(groupId: g, msgId: "m1", member: "GOT_IT", kind: .received, at: Date())

        let rows = GroupReceipts.rows(
            groupMembers: ["ME", "READER", "GOT_IT", "SILENT"],
            sender: "ME",
            states: s.states(groupId: g, msgId: "m1"))

        XCTAssertEqual(rows.count, 3, "the sender is not in their own receipt list")
        XCTAssertFalse(rows.contains { $0.memberKey == "ME" })
        // Order: read, then delivered, then unknown.
        XCTAssertEqual(rows.map(\.memberKey), ["READER", "GOT_IT", "SILENT"])
        XCTAssertTrue(rows[0].hasRead)
        XCTAssertTrue(rows[1].hasReceived)
        XCTAssertFalse(rows[1].hasRead)
        XCTAssertFalse(rows[2].hasReceived)
    }

    func testForgetDropsOnlyThatGroup() {
        let other = UUID()
        let s = GroupReceipts.Store.shared
        s.record(groupId: g, msgId: "m1", member: "A", kind: .read, at: Date())
        s.record(groupId: other, msgId: "m1", member: "A", kind: .read, at: Date())
        s.forget(groupId: g)
        XCTAssertTrue(s.states(groupId: g, msgId: "m1").isEmpty)
        XCTAssertFalse(s.states(groupId: other, msgId: "m1").isEmpty)
    }

    // MARK: Who may receive one (__GROUP_RECEIPT_IOS_1043_2026_11_13__)

    /// iOS 1.0.43 publishes `Caps.mine = resendList` (= 1) and has no
    /// `📑GROUP_RECEIPT📑` in its catalog: it renders a receipt as a bubble.
    func testIOS1043NeverGetsAReceipt() {
        XCTAssertFalse(GroupReceipts.peerReadsReceipts(accountCaps: 1, accountSlotPads: false, sentUsAReceipt: false))
        XCTAssertFalse(GroupReceipts.peerReadsReceipts(accountCaps: 0, accountSlotPads: false, sentUsAReceipt: false))
    }

    /// group-v2 was published by Android builds that predate receipts.
    func testGroupV2AloneIsNotEnough() {
        let caps = RatchetHealSignal.Caps.resendList | RatchetHealSignal.Caps.groupV2
        XCTAssertFalse(GroupReceipts.peerReadsReceipts(accountCaps: caps, accountSlotPads: false, sentUsAReceipt: false))
    }

    func testPaddingCapOrSlotOrProofSuffices() {
        XCTAssertTrue(GroupReceipts.peerReadsReceipts(accountCaps: RatchetHealSignal.Caps.padding, accountSlotPads: false, sentUsAReceipt: false))
        XCTAssertTrue(GroupReceipts.peerReadsReceipts(accountCaps: 1, accountSlotPads: true, sentUsAReceipt: false))
        XCTAssertTrue(GroupReceipts.peerReadsReceipts(accountCaps: 1, accountSlotPads: false, sentUsAReceipt: true))
    }

    /// Two current builds must keep exchanging receipts.
    func testThisBuildQualifies() {
        XCTAssertTrue(GroupReceipts.peerReadsReceipts(accountCaps: RatchetHealSignal.Caps.mine, accountSlotPads: false, sentUsAReceipt: false))
    }

    func testReadersIgnoreKeySpelling() {
        let d = UserDefaults(suiteName: "GroupReceiptsReadersTests")!
        d.removePersistentDomain(forName: "GroupReceiptsReadersTests")
        let saved = GroupReceipts.Readers.defaults
        GroupReceipts.Readers.defaults = d
        defer { GroupReceipts.Readers.defaults = saved }
        XCTAssertFalse(GroupReceipts.Readers.contains("ab+c/d=="))
        GroupReceipts.Readers.note("ab-c_d")
        XCTAssertTrue(GroupReceipts.Readers.contains("ab+c/d=="))
    }
}
