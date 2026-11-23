import XCTest
@testable import OSHI

/// __LORA_FIXED_POSITION_2026_10_28__ The admin frame that actually puts this node on other
/// radios' maps, pinned to bytes computed OUTSIDE this codebase.
///
/// This test exists because of a defect every other kind of test missed. The beacon shipped
/// this morning sent a POSITION_APP packet to the broadcast address; it had unit tests, a
/// policy digest matching two other platforms, and a payload checked against an independent
/// Python encoding of `Position`. All of that was correct and the frame reached no other
/// node: measured with two ThinkNode M1s in one room, ten of them over four minutes arrived
/// nowhere, while a text broadcast from the same radio in the same second arrived in 3
/// seconds, on a mesh where the receiver was reading 36 other nodes' positions correctly.
///
/// The firmware owns a node's position. A client sets it with
/// `AdminMessage{ set_fixed_position }` — field 41 — and the node announces on its own
/// clock. The SAME literals are asserted on Android (`LoRaFixedPositionWireTest`) and on the
/// desktop (`LoRaFixedPositionAdminWireTest`): three clients, one set of bytes.
final class LoRaFixedPositionWireTests: XCTestCase {

    /// The admin payload exactly as `setFixedPosition` builds it. Mirrored here rather than
    /// called, because `setFixedPosition` needs a connected radio — and the point of this
    /// test is the ENCODING, which must not require one.
    private func adminSetFixedPosition(_ lat: Double, _ lon: Double) -> Data {
        var position = ProtoWriter()
        position.fixed32(1, UInt32(bitPattern: Int32((lat * 1e7).rounded())))
        position.fixed32(2, UInt32(bitPattern: Int32((lon * 1e7).rounded())))
        var admin = ProtoWriter()
        admin.bytes(41, position.data)
        return admin.data
    }

    private func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }

    func testSetFixedPositionIsField41WrappingAPosition() {
        // ca02 = field 41, wire type 2. 0a = the Position submessage is 10 bytes.
        XCTAssertEqual("ca020a0d603b8a1b15c060a903", hex(adminSetFixedPosition(46.2044, 6.1432)))
    }

    func testASouthernAndEasternPointKeepsItsSigns() {
        XCTAssertEqual("ca020a0d0008d0eb1548b5205a", hex(adminSetFixedPosition(-33.8688, 151.2093)))
    }

    func testRemoveFixedPositionIsField42ABool() {
        var admin = ProtoWriter()
        admin.varint(42, 1)
        XCTAssertEqual("d00201", hex(admin.data))
    }

    func testTheAdminPortIsSix() {
        XCTAssertEqual(6, MeshtasticManager.adminPortnum)
    }

    /// The Position encoding inside the wrapper is the SAME one `positionPayload` builds —
    /// that encoder was never the problem, only where its bytes were sent.
    func testTheWrappedPositionIsTheShippedEncoder() {
        let standalone = MeshtasticManager.positionPayload(latitude: 46.2044, longitude: 6.1432, timeSec: 0)
        // The shipped encoder appends a time field; the admin frame omits it, so compare the
        // coordinate prefix rather than the whole thing.
        let coords = standalone.prefix(10)
        XCTAssertEqual("0d603b8a1b15c060a903", hex(Data(coords)))
    }
}
