import XCTest
@testable import OSHI

/// __GROUP_GIF_PICKER_2026_11_16__ A GIF picked in a GROUP chat (local pack,
/// GIPHY or an OSHI mascot) must reach every group receiver as an animated
/// GIF: group `photo`, raw GIF bytes untouched, `*.gif` file name — the same
/// wire Android and desktop group senders use, and that iOS
/// (`LazyGroupPhotoView`), Android (`BubbleAsyncImage`) and desktop
/// (`GifBubble.isGifFile`) all animate from the `GIF8` header.
final class GroupGifSendTests: XCTestCase {

    private func mascotGIF() throws -> Data {
        let wave = try XCTUnwrap(OSHIMascotCatalog.named("wave"))
        let url = try XCTUnwrap(OSHIMascotCatalog.gifURL(wave), "mascot GIF not bundled")
        return try Data(contentsOf: url)
    }

    private func attachment(_ data: Data, type: MediaManager.MediaType, name: String) -> MediaManager.MediaAttachment {
        MediaManager.MediaAttachment(
            id: UUID().uuidString, type: type, data: data, thumbnailData: nil,
            fileName: name, fileSize: data.count, timestamp: Date(),
            originalSize: nil, compressionRatio: nil, isCompressed: false, isViewOnce: false)
    }

    func testPickedGifBecomesGroupPhotoWithUntouchedBytes() async throws {
        let gif = try mascotGIF()
        // The picker's own preparation step (`StickerGifPicker.finish`).
        let picked = try await MediaManager().processGIF(gif, fileName: "oshi-wave.gif", source: "test")
        let media = try XCTUnwrap(GroupGifSend.groupMedia(picked))
        XCTAssertEqual(media.type, .photo, "group MediaType has no gif case; a GIF travels as photo")
        XCTAssertEqual(media.data, gif, "bytes must not be re-encoded (a JPEG would arrive frozen)")
        XCTAssertTrue(MediaManager.isGIFData(media.data))
        XCTAssertEqual(media.fileName, "oshi-wave.gif")
    }

    func testFileNameAlwaysEndsInGif() throws {
        let gif = try mascotGIF()
        XCTAssertEqual(GroupGifSend.groupMedia(attachment(gif, type: .gif, name: "giphy-123"))?.fileName, "giphy-123.gif")
        XCTAssertEqual(GroupGifSend.groupMedia(attachment(gif, type: .gif, name: "A.GIF"))?.fileName, "A.GIF")
        let unnamed = try XCTUnwrap(GroupGifSend.groupMedia(attachment(gif, type: .gif, name: "")))
        XCTAssertTrue(unnamed.fileName.hasSuffix(".gif"))
    }

    func testNonGifBytesAreRefused() {
        let jpegish = Data([0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10]) + Data(repeating: 0, count: 64)
        XCTAssertNil(GroupGifSend.groupMedia(attachment(jpegish, type: .gif, name: "fake.gif")))
    }

    /// The stripped GroupMessage that rides in the v2file key message keeps
    /// `photo` + the `.gif` name through encode → decode.
    func testGroupWireKeepsPhotoTypeAndGifName() throws {
        let gif = try mascotGIF()
        let media = try XCTUnwrap(GroupGifSend.groupMedia(attachment(gif, type: .gif, name: "oshi-love.gif")))
        var m = GroupMessage(id: UUID(), groupId: UUID(), senderPublicKey: "sender-key",
                             encryptedContent: Data(), timestamp: Date(),
                             isRead: false, messageChainIndex: 0)
        m.mediaType = media.type
        m.mediaFileName = media.fileName
        m.plaintextContent = ""
        let wire = try XCTUnwrap(GroupWireV2.encodeContent(m))
        let back = try XCTUnwrap(GroupWireV2.decodeContent(wire))
        XCTAssertEqual(back.mediaType, .photo)
        XCTAssertEqual(back.mediaFileName, "oshi-love.gif")
        let obj = try XCTUnwrap(JSONSerialization.jsonObject(with: try XCTUnwrap(Data(base64Encoded: wire))) as? [String: Any])
        XCTAssertEqual(obj["mediaType"] as? String, "photo")
    }

    /// __GROUP_ATTACH_PARITY_2026_11_16__ The contact card a group now sends
    /// is the 1:1 card: `{"publicKey","alias","timestamp"}`, which Android's
    /// `ContactCardWire` requires (timestamp included) and iOS decodes.
    func testGroupContactCardIsTheOneToOneCard() async throws {
        let card = try await MediaManager().createContactCard(publicKey: "pk-ABCDEF0123456789", alias: "Ada")
        XCTAssertEqual(card.type, .contact)
        let obj = try XCTUnwrap(JSONSerialization.jsonObject(with: card.data) as? [String: Any])
        XCTAssertEqual(obj["publicKey"] as? String, "pk-ABCDEF0123456789")
        XCTAssertEqual(obj["alias"] as? String, "Ada")
        XCTAssertNotNil(obj["timestamp"], "Android ContactCardWire needs timestamp")
        let back = try JSONDecoder().decode(ContactCard.self, from: card.data)
        XCTAssertEqual(back.alias, "Ada")
    }
}
