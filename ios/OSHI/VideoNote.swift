//
//  VideoNote.swift
//  OSHI
//
//  __VIDEO_NOTE_2026_09_24__ Round video messages ("video notes").
//
//  A video note IS a normal encrypted video attachment (same bytes path, same
//  blob store, same legacy fallbacks, `mediaType: "video"`, `mime: "video/mp4"`)
//  plus two OPTIONAL wire fields, `videoNote: true` and `durationMs: <int>`.
//  Older clients ignore the keys and show an ordinary video. The shared
//  contract with Android and Desktop is `docs/VIDEO_NOTE_SPEC.md` — change the
//  wire here only together with that file.
//
//  This file: the message-model helpers and the square H.264 re-encoder (app
//  only). Limits, wire metadata and the pure rules are in VideoNoteWire.swift.
//

import Foundation
@preconcurrency import AVFoundation
import CoreGraphics

extension SecureMessage {
    /// Round-bubble rendering: flagged AND actually a video.
    var isVideoNote: Bool { videoNote == true && mediaType == .video }

    var videoNoteMetadata: VideoNoteMetadata? {
        isVideoNote ? VideoNoteMetadata(durationMs: durationMs) : nil
    }

    mutating func applyVideoNote(_ meta: VideoNoteMetadata?) {
        videoNote = meta == nil ? nil : true
        durationMs = meta?.durationMs
    }
}

extension GroupMessage {
    var isVideoNote: Bool { videoNote == true && mediaType == .video }

    var videoNoteMetadata: VideoNoteMetadata? {
        isVideoNote ? VideoNoteMetadata(durationMs: durationMs) : nil
    }

    mutating func applyVideoNote(_ meta: VideoNoteMetadata?) {
        videoNote = meta == nil ? nil : true
        durationMs = meta?.durationMs
    }
}

// MARK: - Encoder

/// Re-encodes a camera recording into the spec's square MP4: centre crop,
/// 480 × 480 H.264 at ~30 fps, AAC mono 64 kbit/s, trimmed to 15.0 s, with NO
/// metadata carried over (no location, no device make/model, no software tag —
/// the writer starts from an empty metadata list and nothing is copied).
enum VideoNoteEncoder {
    enum EncodeError: Error {
        case noVideoTrack
        case readerFailed(String)
        case writerFailed(String)
        case tooShort
    }

    struct Output: Equatable {
        let url: URL
        let durationMs: Int
        let byteCount: Int
    }

    /// Maps a track's natural frame into the square output: orientation
    /// (`preferredTransform`), centre crop, scale to `side`, optional mirror.
    static func squareCropTransform(naturalSize: CGSize,
                                    preferredTransform: CGAffineTransform,
                                    outputSide: CGFloat,
                                    mirror: Bool) -> CGAffineTransform {
        let oriented = CGRect(origin: .zero, size: naturalSize).applying(preferredTransform)
        let w = abs(oriented.width), h = abs(oriented.height)
        let side = max(1, min(w, h))
        let scale = outputSide / side
        // Normalise the orientation transform so the oriented frame starts at 0,0.
        var t = preferredTransform.concatenating(
            CGAffineTransform(translationX: -oriented.minX, y: -oriented.minY))
        t = t.concatenating(CGAffineTransform(translationX: -(w - side) / 2, y: -(h - side) / 2))
        t = t.concatenating(CGAffineTransform(scaleX: scale, y: scale))
        if mirror {
            t = t.concatenating(CGAffineTransform(a: -1, b: 0, c: 0, d: 1, tx: outputSide, ty: 0))
        }
        return t
    }

    static func encode(input: URL, output: URL, mirror: Bool = false) async throws -> Output {
        let asset = AVURLAsset(url: input)
        guard let videoTrack = try await asset.loadTracks(withMediaType: .video).first else {
            throw EncodeError.noVideoTrack
        }
        let audioTrack = try await asset.loadTracks(withMediaType: .audio).first
        let assetDuration = try await asset.load(.duration)
        let naturalSize = try await videoTrack.load(.naturalSize)
        let preferred = try await videoTrack.load(.preferredTransform)

        let seconds = min(max(assetDuration.seconds.isFinite ? assetDuration.seconds : 0, 0), VideoNote.maxDuration)
        guard seconds >= 0.1 else { throw EncodeError.tooShort }
        let range = CMTimeRange(start: .zero, duration: CMTime(seconds: seconds, preferredTimescale: 600))

        let side = CGFloat(VideoNote.dimension)
        let composition = AVMutableVideoComposition()
        composition.renderSize = CGSize(width: side, height: side)
        composition.frameDuration = CMTime(value: 1, timescale: CMTimeScale(VideoNote.frameRate))
        let instruction = AVMutableVideoCompositionInstruction()
        instruction.timeRange = range
        let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: videoTrack)
        layer.setTransform(squareCropTransform(naturalSize: naturalSize, preferredTransform: preferred,
                                               outputSide: side, mirror: mirror), at: .zero)
        instruction.layerInstructions = [layer]
        composition.instructions = [instruction]

        let reader = try AVAssetReader(asset: asset)
        reader.timeRange = range
        let videoOut = AVAssetReaderVideoCompositionOutput(
            videoTracks: [videoTrack],
            videoSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange])
        videoOut.videoComposition = composition
        videoOut.alwaysCopiesSampleData = false
        guard reader.canAdd(videoOut) else { throw EncodeError.readerFailed("video output") }
        reader.add(videoOut)

        var audioOut: AVAssetReaderTrackOutput?
        if let audioTrack {
            let out = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVSampleRateKey: VideoNote.audioSampleRate,
                AVNumberOfChannelsKey: 1,
                AVLinearPCMBitDepthKey: 16,
                AVLinearPCMIsFloatKey: false,
                AVLinearPCMIsBigEndianKey: false,
                AVLinearPCMIsNonInterleaved: false,
            ])
            out.alwaysCopiesSampleData = false
            if reader.canAdd(out) { reader.add(out); audioOut = out }
        }

        try? FileManager.default.removeItem(at: output)
        let writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
        writer.shouldOptimizeForNetworkUse = true
        writer.metadata = []  // spec §3: nothing about the device or the place.

        let videoIn = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: VideoNote.dimension,
            AVVideoHeightKey: VideoNote.dimension,
            AVVideoScalingModeKey: AVVideoScalingModeResizeAspectFill,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: VideoNote.videoBitRate,
                AVVideoExpectedSourceFrameRateKey: VideoNote.frameRate,
                AVVideoMaxKeyFrameIntervalKey: VideoNote.frameRate * 2,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
            ],
        ])
        videoIn.expectsMediaDataInRealTime = false
        guard writer.canAdd(videoIn) else { throw EncodeError.writerFailed("video input") }
        writer.add(videoIn)

        var audioIn: AVAssetWriterInput?
        if audioOut != nil {
            let input = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: VideoNote.audioSampleRate,
                AVNumberOfChannelsKey: 1,
                AVEncoderBitRateKey: VideoNote.audioBitRate,
            ])
            input.expectsMediaDataInRealTime = false
            if writer.canAdd(input) { writer.add(input); audioIn = input }
        }

        guard reader.startReading() else {
            throw EncodeError.readerFailed(reader.error?.localizedDescription ?? "startReading")
        }
        guard writer.startWriting() else {
            reader.cancelReading()
            throw EncodeError.writerFailed(writer.error?.localizedDescription ?? "startWriting")
        }
        writer.startSession(atSourceTime: .zero)

        let lastVideoPTS = LockedTime()
        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                await pump(input: videoIn, output: videoOut, label: "oshi.videonote.v", lastPTS: lastVideoPTS)
            }
            if let audioIn, let audioOut {
                group.addTask {
                    await pump(input: audioIn, output: audioOut, label: "oshi.videonote.a", lastPTS: nil)
                }
            }
        }

        if reader.status == .failed {
            writer.cancelWriting()
            try? FileManager.default.removeItem(at: output)
            throw EncodeError.readerFailed(reader.error?.localizedDescription ?? "read")
        }
        await writer.finishWriting()
        guard writer.status == .completed else {
            try? FileManager.default.removeItem(at: output)
            throw EncodeError.writerFailed(writer.error?.localizedDescription ?? "finish")
        }

        let frameSeconds = 1.0 / Double(VideoNote.frameRate)
        let measured = lastVideoPTS.value.map { $0 + frameSeconds } ?? seconds
        let size = (try? FileManager.default.attributesOfItem(atPath: output.path)[.size] as? Int) ?? 0
        return Output(url: output,
                      durationMs: VideoNoteRecordingClock.durationMs(forRecorded: min(measured, seconds)),
                      byteCount: size)
    }

    private final class LockedTime: @unchecked Sendable {
        private let lock = NSLock()
        private var seconds: Double?
        var value: Double? { lock.lock(); defer { lock.unlock() }; return seconds }
        func set(_ s: Double) { lock.lock(); seconds = max(seconds ?? 0, s); lock.unlock() }
    }

    private static func pump(input: AVAssetWriterInput, output: AVAssetReaderOutput,
                             label: String, lastPTS: LockedTime?) async {
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            let queue = DispatchQueue(label: label)
            var finished = false
            input.requestMediaDataWhenReady(on: queue) {
                while input.isReadyForMoreMediaData && !finished {
                    if let buffer = output.copyNextSampleBuffer() {
                        lastPTS?.set(CMSampleBufferGetPresentationTimeStamp(buffer).seconds)
                        if !input.append(buffer) {
                            finished = true
                        }
                    } else {
                        finished = true
                    }
                    if finished {
                        input.markAsFinished()
                        cont.resume()
                        return
                    }
                }
            }
        }
    }
}

// MARK: - DEBUG capture harness

#if DEBUG
import UIKit

/// `-OSHIScreens -OSHIOpenChat -OSHIVideoNoteDemo`: drops two round video
/// messages (one from Alice, one from me) at the bottom of Alice's thread so the
/// bubble can be captured on a simulator. Never compiled into a release build.
enum VideoNoteDemo {
    /// Seeds the two notes, then hands back Alice's conversation (canonical key)
    /// so the caller can open it.
    static func seedIfRequested(completion: @escaping (Conversation?) -> Void = { _ in }) {
        guard ProcessInfo.processInfo.arguments.contains("-OSHIVideoNoteDemo") else { return }
        NSLog("VIDEONOTE_DEMO start")
        Task.detached(priority: .userInitiated) {
            let clipA: Data, clipB: Data
            do {
                clipA = try await makeClip(avatar: "demo_alice", seconds: 3)
                clipB = try await makeClip(avatar: "demo_bob", seconds: 2)
            } catch {
                NSLog("VIDEONOTE_DEMO clip failed: \(error)")
                return
            }
            NSLog("VIDEONOTE_DEMO clips \(clipA.count) \(clipB.count)")
            // Let the demo loader finish republishing the store first.
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            await MainActor.run {
                guard let mm = MessageManager.shared else { return completion(nil) }
                let me = MessageManager.sharedIdentityManager?.publicKey ?? ""
                let alice = MessageManager.canonicalIdentity(DemoDataManager.aliceKey)
                func note(_ id: String, _ data: Data, incoming: Bool, ms: Int, ago: TimeInterval) -> SecureMessage {
                    let sender = incoming ? alice : me
                    var m = SecureMessage(
                        id: id, senderAddress: incoming ? "Alice" : String(me.prefix(10)),
                        recipientAddress: incoming ? String(me.prefix(10)) : "Alice",
                        encryptedContent: EncryptedMessage(ciphertext: "", signature: "demo_signature",
                                                           senderPublicKey: sender, timestamp: Date()),
                        timestamp: Date().addingTimeInterval(-ago), isRead: true, deliveryStatus: .delivered,
                        senderPublicKey: sender, recipientPublicKey: incoming ? me : alice,
                        plaintextContent: "", mediaAttachment: data, deliveryMethod: .ipfs,
                        mediaType: .video, originalMediaData: data, mediaFileName: VideoNote.fileName())
                    m.applyVideoNote(VideoNoteMetadata(durationMs: ms))
                    return m
                }
                mm.messages.removeAll { $0.id.hasPrefix("videonote-demo-") }
                mm.messages.append(note("videonote-demo-a", clipA, incoming: true, ms: 12_480, ago: 240))
                mm.messages.append(note("videonote-demo-b", clipB, incoming: false, ms: 7_020, ago: 60))
                mm.refreshConversations()
                NSLog("VIDEONOTE_DEMO seeded; conversations=\(mm.conversations.count)")
                completion(mm.conversations.first {
                    MessageManager.canonicalIdentity($0.participantPublicKey) == alice
                })
            }
        }
    }

    /// A short square H.264 clip: the avatar slowly zooming, like a selfie note.
    static func makeClip(avatar: String, seconds: Int) async throws -> Data {
        let side = VideoNote.dimension
        let url = MediaPlaintextTemp.scratchURL(ext: "mp4")
        defer { MediaPlaintextTemp.remove(url) }
        let image = Bundle.main.url(forResource: avatar, withExtension: "jpg")
            .flatMap { try? Data(contentsOf: $0) }.flatMap(UIImage.init(data:))?.cgImage
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: side, AVVideoHeightKey: side,
        ])
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: side, kCVPixelBufferHeightKey as String: side,
        ])
        writer.add(input)
        guard writer.startWriting() else {
            throw writer.error ?? VideoNoteEncoder.EncodeError.writerFailed("demo start")
        }
        writer.startSession(atSourceTime: .zero)
        let fps = 15
        for frame in 0..<(seconds * fps) {
            while !input.isReadyForMoreMediaData {
                if writer.status == .failed { throw writer.error ?? VideoNoteEncoder.EncodeError.writerFailed("demo") }
                try await Task.sleep(nanoseconds: 2_000_000)
            }
            guard let pool = adaptor.pixelBufferPool else { break }
            var buffer: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer)
            guard let buffer else { break }
            CVPixelBufferLockBaseAddress(buffer, [])
            if let ctx = CGContext(data: CVPixelBufferGetBaseAddress(buffer), width: side, height: side,
                                   bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
                                   space: CGColorSpaceCreateDeviceRGB(),
                                   bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) {
                ctx.setFillColor(UIColor(red: 0.337, green: 0.086, blue: 0.925, alpha: 1).cgColor)
                ctx.fill(CGRect(x: 0, y: 0, width: side, height: side))
                if let image {
                    let zoom = 1.0 + 0.08 * Double(frame) / Double(seconds * fps)
                    let w = Double(side) * zoom
                    ctx.draw(image, in: CGRect(x: (Double(side) - w) / 2, y: (Double(side) - w) / 2, width: w, height: w))
                }
            }
            CVPixelBufferUnlockBaseAddress(buffer, [])
            adaptor.append(buffer, withPresentationTime: CMTime(value: CMTimeValue(frame), timescale: CMTimeScale(fps)))
        }
        input.markAsFinished()
        await writer.finishWriting()
        return try Data(contentsOf: url)
    }
}
#endif
