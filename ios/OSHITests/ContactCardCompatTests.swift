//
//  ContactCardCompatTests.swift
//  OSHITests
//
//  __CONTACT_CARD_COMPAT_2026_09_27__ "I received a kind of contact picture of
//  75 bytes but nothing I can click."
//
//  The 75 bytes are Android's welcome contact card as 1.6.x wrote it — kotlinx
//  drops the null `timestamp` — forwarded to an iPhone. The synthesized
//  `ContactCard` decoder required `timestamp`, threw, and the bubble fell back to
//  a grey placeholder with no action. The fixtures are the literal wire bytes.
//

import XCTest
@testable import OSHI

final class ContactCardCompatTests: XCTestCase {

    private let hugoKey = "eAbMQUdW6UtufTtaHqDJtkC24fKa3c1nhaGN/muFE3A="

    /// Byte for byte what an Android welcome card forward delivers.
    private var legacyAndroidCard: Data {
        Data(#"{"publicKey":"eAbMQUdW6UtufTtaHqDJtkC24fKa3c1nhaGN/muFE3A=","alias":"Hugo"}"#.utf8)
    }

    func testReportedPayloadIsSeventyFiveBytes() {
        XCTAssertEqual(legacyAndroidCard.count, 75)
    }

    func testCardWithoutTimestampDecodes() {
        let card = ContactCard.parse(legacyAndroidCard)
        XCTAssertEqual(card?.publicKey, hugoKey)
        XCTAssertEqual(card?.alias, "Hugo")
    }

    func testDecodeContactCardAcceptsLegacyAndroidAttachment() throws {
        let attachment = MediaManager.MediaAttachment(
            id: "m1", type: .contact, data: legacyAndroidCard, thumbnailData: nil,
            fileName: "a.vcf", fileSize: legacyAndroidCard.count, timestamp: Date(),
            originalSize: nil, compressionRatio: nil, isCompressed: false)
        let card = try MediaManager().decodeContactCard(from: attachment)
        XCTAssertEqual(card.publicKey, hugoKey)
    }

    /// What Android's `ContactCardWire.encode` sends today: kotlinx prints the
    /// Apple-seconds double in exponent notation.
    func testAndroidExponentTimestampDecodes() {
        let json = Data(#"{"publicKey":"K","alias":"Bob","timestamp":8.12345678123E8}"#.utf8)
        let card = ContactCard.parse(json)
        XCTAssertEqual(card?.publicKey, "K")
        XCTAssertEqual(card?.timestamp.timeIntervalSinceReferenceDate ?? 0, 812_345_678.123, accuracy: 0.01)
    }

    func testOwnEncoderRoundTrips() throws {
        let original = ContactCard(publicKey: "K", alias: "Claire",
                                   timestamp: Date(timeIntervalSinceReferenceDate: 788_918_400))
        let data = try JSONEncoder().encode(original)
        let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        XCTAssertEqual(Set((obj ?? [:]).keys), ["publicKey", "alias", "timestamp"],
                       "the wire shape shipped builds decode must not change")
        let back = ContactCard.parse(data)
        XCTAssertEqual(back?.alias, "Claire")
        XCTAssertEqual(back?.timestamp.timeIntervalSinceReferenceDate ?? 0, 788_918_400, accuracy: 0.001)
    }

    func testUnixTimestampsAndISOStringsAreUnderstood() {
        let unixSeconds = ContactCard.parse(Data(#"{"publicKey":"K","timestamp":1790000000}"#.utf8))
        XCTAssertEqual(unixSeconds?.timestamp.timeIntervalSince1970 ?? 0, 1_790_000_000, accuracy: 1)
        let unixMillis = ContactCard.parse(Data(#"{"publicKey":"K","timestamp":1790000000000}"#.utf8))
        XCTAssertEqual(unixMillis?.timestamp.timeIntervalSince1970 ?? 0, 1_790_000_000, accuracy: 1)
        let iso = ContactCard.parse(Data(#"{"publicKey":"K","timestamp":"2026-09-27T10:00:00Z"}"#.utf8))
        XCTAssertNotNil(iso)
    }

    func testBlankAliasBecomesNil() {
        XCTAssertNil(ContactCard.parse(Data(#"{"publicKey":"K","alias":"  "}"#.utf8))?.alias)
        XCTAssertNil(ContactCard.parse(Data(#"{"publicKey":"K","alias":null}"#.utf8))?.alias)
    }

    /// The shape Android's `ContactShareManager.exportToVCard` produces.
    func testAndroidVCardIsUnderstood() {
        let vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Eve Adams\r\nN:Adams;Eve;;;\r\nNOTE:OSHI Public Key: VKEY\r\nEND:VCARD\r\n"
        let card = ContactCard.parse(Data(vcard.utf8))
        XCTAssertEqual(card?.publicKey, "VKEY")
        XCTAssertEqual(card?.alias, "Eve Adams")
    }

    func testUnreadableBytesYieldNil() {
        XCTAssertNil(ContactCard.parse(Data([0x00, 0x01, 0x02])))
        XCTAssertNil(ContactCard.parse(Data(#"{"alias":"no key"}"#.utf8)))
        XCTAssertNil(ContactCard.parse(Data(#"{"publicKey":""}"#.utf8)))
        XCTAssertNil(ContactCard.parse(Data("BEGIN:VCARD\nFN:No Key\nEND:VCARD".utf8)))
    }
}
