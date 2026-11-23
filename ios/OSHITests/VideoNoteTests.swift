//
//  VideoNoteTests.swift
//  OSHITests
//
//  __VIDEO_NOTE_2026_09_24__ The round-video wire contract (docs/VIDEO_NOTE_SPEC.md)
//  and the pure logic around it: metadata round-trip on every carrier, older
//  payloads staying normal videos, the 15 s cap, the square encoder, and the
//  composer rule that hides the capture buttons while there is text.
//

import XCTest
import AVFoundation
@testable import OSHI

final class VideoNoteTests: XCTestCase {

    // MARK: - Helpers

    private func sortedJSON<T: Encodable>(_ value: T) throws -> String {
        let enc = JSONEncoder()
        enc.outputFormatting = [.sortedKeys]
        return String(data: try enc.encode(value), encoding: .utf8) ?? ""
    }

    private func keyMessage(mediaType: String = "video", mime: String = "video/mp4",
                            videoNote: Bool? = nil, durationMs: Int? = nil) -> V2FileKeyMessage {
        V2FileKeyMessage(kind: V2FileKeyMessage.kindValue, blobId: "blob-1", fileKey: "a2V5",
                         fileNonce: "bm9uY2U=", chunkCount: 1, manifest: "bWFu",
                         filename: "videonote_1758711234567.mp4", mime: mime,
                         mediaType: mediaType, isViewOnce: nil, groupMessage: nil,
                         videoNote: videoNote, durationMs: durationMs)
    }

    private func decodeKey(_ json: String) throws -> V2FileKeyMessage {
        try JSONDecoder().decode(V2FileKeyMessage.self, from: Data(json.utf8))
    }

    private let baseKeyJSON = #""kind":"v2file","blobId":"b","fileKey":"k","fileNonce":"n","chunkCount":1,"manifest":"m","filename":"videonote_1.mp4","mime":"video/mp4""#

    // MARK: - V2 file key (spec §2.A)

    func testV2KeyRoundTripCarriesBothFields() throws {
        let json = try sortedJSON(keyMessage(videoNote: true, durationMs: 12480))
        XCTAssertTrue(json.contains(#""videoNote":true"#), json)
        XCTAssertTrue(json.contains(#""durationMs":12480"#), json)
        let back = try decodeKey(json)
        XCTAssertEqual(back.videoNoteMetadata, VideoNoteMetadata(durationMs: 12480))
    }

    func testV2KeyWithoutFieldsIsANormalVideoAndByteIdentical() throws {
        let json = try sortedJSON(keyMessage())
        XCTAssertFalse(json.contains("videoNote"))
        XCTAssertFalse(json.contains("durationMs"))
        let expected = #"{"blobId":"blob-1","chunkCount":1,"fileKey":"a2V5","fileNonce":"bm9uY2U=","filename":"videonote_1758711234567.mp4","kind":"v2file","manifest":"bWFu","mediaType":"video","mime":"video\/mp4"}"#
        XCTAssertEqual(json, expected, "a normal video's payload must not change")
        XCTAssertNil(try decodeKey(json).videoNoteMetadata)
    }

    func testSpecVectorA() throws {
        let key = try decodeKey("{\(baseKeyJSON),\"mediaType\":\"video\",\"videoNote\":true,\"durationMs\":12480}")
        XCTAssertEqual(key.videoNoteMetadata?.durationMs, 12480)
    }

    func testVideoNoteFalseIsNormalVideo() throws {
        let key = try decodeKey("{\(baseKeyJSON),\"mediaType\":\"video\",\"videoNote\":false,\"durationMs\":3000}")
        XCTAssertNil(key.videoNoteMetadata)
    }

    func testDurationIsClampedAndTolerant() throws {
        XCTAssertEqual(try decodeKey("{\(baseKeyJSON),\"mediaType\":\"video\",\"videoNote\":true,\"durationMs\":99999}")
                        .videoNoteMetadata?.durationMs, 15000)
        let negative = try decodeKey("{\(baseKeyJSON),\"mediaType\":\"video\",\"videoNote\":true,\"durationMs\":-3}")
        XCTAssertNotNil(negative.videoNoteMetadata)
        XCTAssertNil(negative.videoNoteMetadata?.durationMs)
        XCTAssertEqual(try decodeKey("{\(baseKeyJSON),\"mediaType\":\"video\",\"videoNote\":true,\"durationMs\":1234.6}")
                        .videoNoteMetadata?.durationMs, 1235)
        let garbage = try decodeKey("{\(baseKeyJSON),\"mediaType\":\"video\",\"videoNote\":true,\"durationMs\":\"abc\"}")
        XCTAssertNotNil(garbage.videoNoteMetadata, "a bad duration must not un-note the message")
        XCTAssertNil(garbage.videoNoteMetadata?.durationMs)
    }

    func testMistypedFlagNeverDropsTheFile() throws {
        let key = try decodeKey("{\(baseKeyJSON),\"mediaType\":\"video\",\"videoNote\":\"yes\"}")
        XCTAssertEqual(key.blobId, "b", "the file itself still decodes")
        XCTAssertNil(key.videoNoteMetadata)
    }

    func testFlagOnAPhotoIsIgnored() throws {
        let key = try decodeKey(#"{"kind":"v2file","blobId":"b","fileKey":"k","fileNonce":"n","chunkCount":1,"manifest":"m","filename":"p.jpg","mime":"image/jpeg","mediaType":"image","videoNote":true}"#)
        XCTAssertNil(key.videoNoteMetadata)
    }

    func testMimeAloneIdentifiesAVideo() throws {
        let key = try decodeKey("{\(baseKeyJSON),\"videoNote\":true,\"durationMs\":800}")
        XCTAssertEqual(key.videoNoteMetadata?.durationMs, 800)
    }

    // MARK: - Legacy media JSON (spec §2.C) and Android direct form

    func testLegacyDictApplyAndParse() throws {
        var dict: [String: Any] = ["type": "media", "mediaType": "video", "content": "AAAA", "size": 3]
        VideoNoteMetadata(durationMs: 9030).apply(to: &dict)
        let data = try JSONSerialization.data(withJSONObject: dict)
        let back = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(VideoNoteMetadata.parse(back, isVideo: true), VideoNoteMetadata(durationMs: 9030))
        XCTAssertEqual(back["videoNote"] as? Bool, true)
    }

    func testLegacyDictWithoutFieldsIsNotANote() {
        XCTAssertNil(VideoNoteMetadata.parse(["type": "media", "mediaType": "video"], isVideo: true))
    }

    func testNumericOneIsNotTrue() throws {
        let obj = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(#"{"videoNote":1,"durationMs":true}"#.utf8)) as? [String: Any])
        XCTAssertNil(VideoNoteMetadata.parse(obj, isVideo: true), "only a JSON boolean true counts")
    }

    func testLegacyReceiveParserSetsTheNote() {
        var msg = SecureMessage(id: "m1", senderAddress: "a", recipientAddress: "b",
                                encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: "a", timestamp: Date()),
                                timestamp: Date(), isRead: false, deliveryStatus: .delivered)
        let json = #"{"type":"media","mediaType":"video","content":"AAAAIGZ0eXBpc29t","size":12,"isViewOnce":false,"fileName":"videonote_1.mp4","videoNote":true,"durationMs":12480}"#
        XCTAssertTrue(MessageManager.parseAndStoreMedia(json, for: &msg))
        XCTAssertTrue(msg.isVideoNote)
        XCTAssertEqual(msg.durationMs, 12480)

        var android = SecureMessage(id: "m2", senderAddress: "a", recipientAddress: "b",
                                    encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: "a", timestamp: Date()),
                                    timestamp: Date(), isRead: false, deliveryStatus: .delivered)
        XCTAssertTrue(MessageManager.parseAndStoreMedia(#"{"type":"VIDEO","data":"AAAA","videoNote":true,"durationMs":4000}"#, for: &android))
        XCTAssertEqual(android.videoNoteMetadata, VideoNoteMetadata(durationMs: 4000))

        var old = SecureMessage(id: "m3", senderAddress: "a", recipientAddress: "b",
                                encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: "a", timestamp: Date()),
                                timestamp: Date(), isRead: false, deliveryStatus: .delivered)
        XCTAssertTrue(MessageManager.parseAndStoreMedia(#"{"type":"media","mediaType":"video","content":"AAAA","size":3}"#, for: &old))
        XCTAssertFalse(old.isVideoNote, "an older sender's video stays a normal video")
    }

    // MARK: - Local persistence (spec §2.F)

    func testSecureMessageRoundTripAndNormalMessageUnchanged() throws {
        var msg = SecureMessage(id: "m1", senderAddress: "a", recipientAddress: "b",
                                encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: "a", timestamp: Date(timeIntervalSince1970: 0)),
                                timestamp: Date(timeIntervalSince1970: 0), isRead: false, deliveryStatus: .sent,
                                mediaType: .video)
        let plainJSON = try sortedJSON(msg)
        XCTAssertFalse(plainJSON.contains("videoNote"), "nil is not encoded")
        XCTAssertFalse(try JSONDecoder().decode(SecureMessage.self, from: Data(plainJSON.utf8)).isVideoNote)

        msg.applyVideoNote(VideoNoteMetadata(durationMs: 7000))
        let back = try JSONDecoder().decode(SecureMessage.self, from: try JSONEncoder().encode(msg))
        XCTAssertTrue(back.isVideoNote)
        XCTAssertEqual(back.durationMs, 7000)

        msg.applyVideoNote(nil)
        XCTAssertNil(msg.videoNote)
        XCTAssertNil(msg.durationMs)
    }

    func testGroupMessageRoundTripAndTolerance() throws {
        var gm = GroupMessage(id: UUID(), groupId: UUID(), senderPublicKey: "s", encryptedContent: Data(),
                              timestamp: Date(timeIntervalSince1970: 1000), mediaType: .video,
                              mediaFileName: "videonote_1.mp4")
        XCTAssertFalse(try sortedJSON(gm).contains("videoNote"))
        gm.applyVideoNote(VideoNoteMetadata(durationMs: 9030))
        let data = try JSONEncoder().encode(gm)
        let back = try JSONDecoder().decode(GroupMessage.self, from: data)
        XCTAssertEqual(back.videoNoteMetadata, VideoNoteMetadata(durationMs: 9030))
        XCTAssertEqual(back.id, gm.id)
        XCTAssertEqual(back.mediaFileName, "videonote_1.mp4")

        // A mistyped flag from some peer must not lose the message.
        var obj = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        obj["videoNote"] = "yes"
        obj["durationMs"] = "long"
        let tolerant = try JSONDecoder().decode(GroupMessage.self, from: JSONSerialization.data(withJSONObject: obj))
        XCTAssertEqual(tolerant.id, gm.id)
        XCTAssertFalse(tolerant.isVideoNote)
    }

    func testGroupPayloadInsideV2FileKeyCarriesTheNote() throws {
        var gm = GroupMessage(id: UUID(), groupId: UUID(), senderPublicKey: "s", encryptedContent: Data(),
                              timestamp: Date(), mediaType: .video)
        gm.applyVideoNote(VideoNoteMetadata(durationMs: 5000))
        let b64 = try JSONEncoder().encode(gm).base64EncodedString()
        let key = V2FileKeyMessage(kind: "v2file", blobId: "b", fileKey: "k", fileNonce: "n", chunkCount: 1,
                                   manifest: "m", filename: "videonote_1.mp4", mime: "video/mp4",
                                   mediaType: "video", isViewOnce: nil, groupMessage: b64,
                                   videoNote: true, durationMs: 5000)
        let back = try decodeKey(try sortedJSON(key))
        XCTAssertEqual(back.videoNoteMetadata?.durationMs, 5000)
        let inner = try JSONDecoder().decode(GroupMessage.self, from: try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(back.groupMessage))))
        XCTAssertTrue(inner.isVideoNote)
    }

    // MARK: - 15 s cap

    func testClockCap() {
        XCTAssertEqual(VideoNoteRecordingClock.progress(elapsed: 0), 0)
        XCTAssertEqual(VideoNoteRecordingClock.progress(elapsed: 7.5), 0.5, accuracy: 1e-9)
        XCTAssertEqual(VideoNoteRecordingClock.progress(elapsed: 20), 1)
        XCTAssertFalse(VideoNoteRecordingClock.shouldAutoStop(elapsed: 14.99))
        XCTAssertTrue(VideoNoteRecordingClock.shouldAutoStop(elapsed: 15.0))
        XCTAssertEqual(VideoNoteRecordingClock.remaining(elapsed: 4), 11)
        XCTAssertEqual(VideoNoteRecordingClock.durationMs(forRecorded: 15.4), 15000)
        XCTAssertEqual(VideoNoteRecordingClock.durationMs(forRecorded: 3.2004), 3200)
        XCTAssertEqual(VideoNoteRecordingClock.durationMs(forRecorded: .nan), 0)
        XCTAssertTrue(VideoNoteRecordingClock.acceptsForSend(duration: 15.4))
        XCTAssertFalse(VideoNoteRecordingClock.acceptsForSend(duration: 15.6))
        XCTAssertFalse(VideoNoteRecordingClock.acceptsForSend(duration: 0.2))
        XCTAssertEqual(VideoNote.formatted(ms: 12480), "0:12")
        XCTAssertLessThanOrEqual(VideoNote.estimatedMaxBytes(seconds: 15), 3_000_000, "15 s must fit in ~3 MB")
        XCTAssertTrue(VideoNote.fileName(now: Date(timeIntervalSince1970: 1)).hasPrefix("videonote_1000"))
    }

    // MARK: - Composer state machine

    func testComposerHidesCaptureButtonsWhileThereIsText() {
        XCTAssertTrue(ComposerChrome.showsCaptureButtons(text: ""))
        XCTAssertTrue(ComposerChrome.showsCaptureButtons(text: "  \n "), "whitespace is not something to send")
        XCTAssertFalse(ComposerChrome.showsCaptureButtons(text: "h"))
        XCTAssertFalse(ComposerChrome.showsCaptureButtons(text: " salut "))
        XCTAssertTrue(ComposerChrome.isExpanded(text: "hello"))
        XCTAssertEqual(ComposerChrome.lineLimit(expanded: false), 1...5)
        XCTAssertEqual(ComposerChrome.lineLimit(expanded: true), 1...8)
        // Clearing the text brings them back.
        var text = "draft"
        XCTAssertFalse(ComposerChrome.showsCaptureButtons(text: text))
        text = ""
        XCTAssertTrue(ComposerChrome.showsCaptureButtons(text: text))
    }

    // MARK: - Encoder

    func testSquareCropTransformLandscape() {
        let t = VideoNoteEncoder.squareCropTransform(naturalSize: CGSize(width: 640, height: 480),
                                                     preferredTransform: .identity, outputSide: 480, mirror: false)
        XCTAssertEqual(CGPoint(x: 80, y: 0).applying(t).x, 0, accuracy: 1e-6)
        XCTAssertEqual(CGPoint(x: 560, y: 480).applying(t).x, 480, accuracy: 1e-6)
        let m = VideoNoteEncoder.squareCropTransform(naturalSize: CGSize(width: 640, height: 480),
                                                     preferredTransform: .identity, outputSide: 480, mirror: true)
        XCTAssertEqual(CGPoint(x: 80, y: 0).applying(m).x, 480, accuracy: 1e-6)
    }

    func testSquareCropTransformPortraitRotation() {
        // A 90° capture: 640×480 buffer displayed as 480×640.
        let rot = CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: 480, ty: 0)
        let t = VideoNoteEncoder.squareCropTransform(naturalSize: CGSize(width: 640, height: 480),
                                                     preferredTransform: rot, outputSide: 480, mirror: false)
        let box = CGRect(x: 0, y: 80, width: 480, height: 480)  // oriented centre square
        let corners = [CGPoint(x: 80, y: 0), CGPoint(x: 560, y: 480)].map { $0.applying(t) }
        for c in corners {
            XCTAssertGreaterThanOrEqual(c.x, -1e-6); XCTAssertLessThanOrEqual(c.x, 480 + 1e-6)
            XCTAssertGreaterThanOrEqual(c.y, -1e-6); XCTAssertLessThanOrEqual(c.y, 480 + 1e-6)
        }
        XCTAssertEqual(box.width, 480)
    }

    /// End to end on a synthetic 17 s landscape clip: square 480×480 H.264,
    /// trimmed to the 15 s cap, no metadata, well under 3 MB.
    func testEncoderProducesSquareCappedClipWithoutMetadata() async throws {
        let src = FileManager.default.temporaryDirectory.appendingPathComponent("vn_src_\(UUID().uuidString).mov")
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("vn_out_\(UUID().uuidString).mp4")
        defer { try? FileManager.default.removeItem(at: src); try? FileManager.default.removeItem(at: out) }
        try await Self.writeSyntheticClip(to: src, seconds: 17, fps: 10, size: CGSize(width: 640, height: 480))

        let result = try await VideoNoteEncoder.encode(input: src, output: out)
        XCTAssertLessThanOrEqual(result.durationMs, VideoNote.hardCapMs)
        XCTAssertGreaterThan(result.durationMs, 14_000)
        XCTAssertLessThan(result.byteCount, 3_000_000)

        let asset = AVURLAsset(url: out)
        let tracks = try await asset.loadTracks(withMediaType: .video)
        let track = try XCTUnwrap(tracks.first)
        let size = try await track.load(.naturalSize)
        XCTAssertEqual(size.width, 480); XCTAssertEqual(size.height, 480)
        let duration = try await asset.load(.duration).seconds
        XCTAssertLessThanOrEqual(duration, VideoNote.maxDuration + VideoNote.sendSlack)
        let metadata = try await asset.load(.metadata)
        XCTAssertTrue(metadata.filter { $0.commonKey == .commonKeyLocation || $0.commonKey == .commonKeyMake
                                        || $0.commonKey == .commonKeyModel || $0.commonKey == .commonKeySoftware }.isEmpty)
    }

    private static func writeSyntheticClip(to url: URL, seconds: Int, fps: Int, size: CGSize) async throws {
        let writer = try AVAssetWriter(outputURL: url, fileType: .mov)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: Int(size.width), AVVideoHeightKey: Int(size.height),
        ])
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: Int(size.width), kCVPixelBufferHeightKey as String: Int(size.height),
        ])
        writer.add(input)
        XCTAssertTrue(writer.startWriting())
        writer.startSession(atSourceTime: .zero)
        for frame in 0..<(seconds * fps) {
            while !input.isReadyForMoreMediaData { try await Task.sleep(nanoseconds: 2_000_000) }
            guard let pool = adaptor.pixelBufferPool else { break }
            var buffer: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer)
            guard let buffer else { break }
            CVPixelBufferLockBaseAddress(buffer, [])
            if let base = CVPixelBufferGetBaseAddress(buffer) {
                memset(base, Int32(frame * 7 % 255), CVPixelBufferGetDataSize(buffer))
            }
            CVPixelBufferUnlockBaseAddress(buffer, [])
            adaptor.append(buffer, withPresentationTime: CMTime(value: CMTimeValue(frame), timescale: CMTimeScale(fps)))
        }
        input.markAsFinished()
        await writer.finishWriting()
        XCTAssertEqual(writer.status, .completed)
    }
}
