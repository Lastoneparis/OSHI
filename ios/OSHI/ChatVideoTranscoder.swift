//
//  ChatVideoTranscoder.swift
//  OSHI
//
//  __CHAT_VIDEO_BITRATE_2026_10_09__ « quand j'envoie une vidéo le poids reste
//  trop grand, 17 Mo pour 9 s ».
//
//  That number was reproducible and the cause is structural, not a tuning
//  mistake. `MediaManager.compressVideo` walks a ladder of
//  `AVAssetExportSession` presets and takes the FIRST one whose estimated
//  output fits the size budget. For a clip sent with `.auto` and no mesh peer
//  in range the budget is the 200 MB cloud ceiling, so 1080p always "fits" —
//  and `AVAssetExportPreset1920x1080` encodes at roughly 12-15 Mbit/s. Nine
//  seconds of that is ~17 MB. The ladder was working exactly as written; what
//  it cannot do is pick a BITRATE, because a preset only caps resolution.
//
//  A chat video does not need the biggest encode that fits under a ceiling, it
//  needs the bitrate a phone bubble can show. This transcoder sets that
//  explicitly: 720p-class at ~2.3 Mbit/s, which puts the same 9 s clip at
//  roughly 2.5-3 MB and still looks the same in a bubble.
//
//  It also sets `shouldOptimizeForNetworkUse`, which moves the `moov` atom to
//  the front of the file. Without it a receiver must hold the whole file before
//  the first frame can be decoded — part of why playback felt slow to start.
//
//  Modelled on `VideoNote`'s encoder (the same reader/writer pump), but
//  aspect-preserving instead of square-cropped, since this path carries an
//  ordinary video rather than a round video note.
//

import Foundation
import AVFoundation
import CoreMedia

enum ChatVideoTranscoder {

    // MARK: - Tuning

    /// Longest output side, in pixels, best first. 1280 is 720p class: the size
    /// a phone screen actually shows, and the point where h.264 stops paying
    /// for detail nobody can see in a chat bubble.
    static let longSideLadder = [1280, 960, 640]

    /// Bits per pixel per frame. 1280×720 at 30 fps lands at ~2.35 Mbit/s,
    /// which is the band WhatsApp and Signal send in.
    static let bitsPerPixelPerFrame = 0.085

    /// Never go below this, whatever the budget says: under ~600 kbit/s at
    /// 720p h.264 becomes visibly mushy, and a video the recipient cannot make
    /// out is a worse outcome than one that is a megabyte larger.
    static let minVideoBitRate = 600_000

    static let maxFrameRate = 30
    static let audioBitRate = 96_000
    static let audioSampleRate: Double = 44_100

    enum TranscodeError: LocalizedError {
        case noVideoTrack
        case readerFailed(String)
        case writerFailed(String)

        var errorDescription: String? {
            switch self {
            case .noVideoTrack:         return "The file has no video track."
            case .readerFailed(let s):  return "Could not read the video (\(s))."
            case .writerFailed(let s):  return "Could not write the video (\(s))."
            }
        }
    }

    struct Plan {
        let width: Int
        let height: Int
        let frameRate: Int
        let videoBitRate: Int
        /// What the encode is expected to weigh, container overhead included.
        let estimatedBytes: Int

        var label: String { "\(width)x\(height)@\(videoBitRate / 1000)kbps" }
    }

    // MARK: - Planning

    /// Picks the output size and bitrate for `naturalSize` over `seconds`,
    /// staying inside `budgetBytes` when that is possible without going below
    /// `minVideoBitRate`.
    ///
    /// Pure and `static` so it can be tested without an asset: the sizing rule
    /// is the part of this file most likely to be wrong, and it is the part a
    /// test can actually pin down.
    static func plan(naturalSize: CGSize,
                     seconds: Double,
                     sourceFrameRate: Double,
                     budgetBytes: Int) -> Plan {
        let w = abs(naturalSize.width), h = abs(naturalSize.height)
        guard w > 0, h > 0 else {
            return Plan(width: 640, height: 640, frameRate: maxFrameRate,
                        videoBitRate: minVideoBitRate, estimatedBytes: 0)
        }
        let sourceLongSide = Int(max(w, h).rounded())
        let fps = sourceFrameRate > 0
            ? min(Double(maxFrameRate), sourceFrameRate)
            : Double(maxFrameRate)
        let duration = max(seconds, 0.1)

        var best: Plan?
        for candidate in longSideLadder {
            // Never upscale: a 640-wide source stays 640 wide.
            let longSide = min(candidate, sourceLongSide)
            let (ow, oh) = evenDimensions(for: naturalSize, longSide: longSide)
            let pixels = Double(ow * oh)
            let ideal = Int((pixels * bitsPerPixelPerFrame * fps).rounded())
            let rate = max(minVideoBitRate, ideal)
            let bytes = estimatedBytes(videoBitRate: rate, seconds: duration)
            let p = Plan(width: ow, height: oh, frameRate: Int(fps.rounded()),
                         videoBitRate: rate, estimatedBytes: bytes)
            if best == nil { best = p }           // remember the smallest-so-far
            if bytes <= budgetBytes { return p }  // first rung that fits wins
            best = p
        }
        // Nothing fit. Return the smallest rung rather than refusing: the
        // caller owns the refusal decision (`refusalCeiling` in MediaManager),
        // and a clip that is over budget but watchable beats a failed send.
        return best!
    }

    /// Encoded size for a bitrate over a duration, with 5% container overhead
    /// and a 16 KB floor for the moov/atom headers.
    static func estimatedBytes(videoBitRate: Int, seconds: Double) -> Int {
        let bits = Double(videoBitRate + audioBitRate) * max(seconds, 0)
        return Int((bits / 8.0) * 1.05) + 16_384
    }

    /// Scales `size` so its long side is `longSide`, rounding both sides to
    /// even numbers — h.264 chroma subsampling requires even dimensions, and an
    /// odd one is rejected or silently padded by the encoder.
    static func evenDimensions(for size: CGSize, longSide: Int) -> (Int, Int) {
        let w = abs(size.width), h = abs(size.height)
        let scale = Double(longSide) / Double(max(w, h))
        let ow = max(2, Int((Double(w) * scale).rounded()))
        let oh = max(2, Int((Double(h) * scale).rounded()))
        return (ow - (ow % 2), oh - (oh % 2))
    }

    // MARK: - Transcode

    /// Re-encodes `source` into `output` at a chat-appropriate bitrate.
    /// Returns the plan that was used, so the caller can log what it got.
    @discardableResult
    static func transcode(source: URL,
                          to output: URL,
                          budgetBytes: Int) async throws -> Plan {
        let asset = AVAsset(url: source)
        guard let videoTrack = try await asset.loadTracks(withMediaType: .video).first else {
            throw TranscodeError.noVideoTrack
        }
        let audioTrack = try await asset.loadTracks(withMediaType: .audio).first

        let naturalSize = try await videoTrack.load(.naturalSize)
        let preferred = try await videoTrack.load(.preferredTransform)
        let nominalFPS = try await videoTrack.load(.nominalFrameRate)
        let duration = try await asset.load(.duration)
        let seconds = duration.seconds.isFinite ? max(duration.seconds, 0) : 0

        // The transform tells us how the frames are MEANT to be shown. A
        // portrait iPhone clip is stored landscape with a 90° rotation, so
        // planning against `naturalSize` alone would size it the wrong way
        // round and letterbox the result.
        let displaySize = naturalSize.applying(preferred)
        let orientedSize = CGSize(width: abs(displaySize.width),
                                  height: abs(displaySize.height))

        let plan = plan(naturalSize: orientedSize,
                        seconds: seconds,
                        sourceFrameRate: Double(nominalFPS),
                        budgetBytes: budgetBytes)

        let renderSize = CGSize(width: plan.width, height: plan.height)
        let composition = AVMutableVideoComposition()
        composition.renderSize = renderSize
        composition.frameDuration = CMTime(value: 1,
                                           timescale: CMTimeScale(max(1, plan.frameRate)))
        let instruction = AVMutableVideoCompositionInstruction()
        instruction.timeRange = CMTimeRange(start: .zero, duration: duration)
        let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: videoTrack)
        layer.setTransform(fitTransform(naturalSize: naturalSize,
                                        preferredTransform: preferred,
                                        renderSize: renderSize),
                           at: .zero)
        instruction.layerInstructions = [layer]
        composition.instructions = [instruction]

        let reader = try AVAssetReader(asset: asset)
        let videoOut = AVAssetReaderVideoCompositionOutput(
            videoTracks: [videoTrack],
            videoSettings: [kCVPixelBufferPixelFormatTypeKey as String:
                                kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange])
        videoOut.videoComposition = composition
        videoOut.alwaysCopiesSampleData = false
        guard reader.canAdd(videoOut) else { throw TranscodeError.readerFailed("video output") }
        reader.add(videoOut)

        var audioOut: AVAssetReaderTrackOutput?
        if let audioTrack {
            let out = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVSampleRateKey: audioSampleRate,
                AVNumberOfChannelsKey: 2,
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
        // Puts the moov atom at the FRONT of the file. Without this the whole
        // file has to arrive before the first frame can be decoded, which is
        // half of "it takes a while when I tap the video".
        writer.shouldOptimizeForNetworkUse = true
        // Nothing about the device, the software or the place travels with a
        // message. Same rule as the video-note encoder.
        writer.metadata = []

        let videoIn = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: plan.width,
            AVVideoHeightKey: plan.height,
            AVVideoScalingModeKey: AVVideoScalingModeResizeAspect,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: plan.videoBitRate,
                AVVideoExpectedSourceFrameRateKey: plan.frameRate,
                // A keyframe every two seconds: enough for scrubbing without
                // spending a large share of the bitrate on I-frames.
                AVVideoMaxKeyFrameIntervalKey: plan.frameRate * 2,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
            ],
        ])
        videoIn.expectsMediaDataInRealTime = false
        guard writer.canAdd(videoIn) else { throw TranscodeError.writerFailed("video input") }
        writer.add(videoIn)

        var audioIn: AVAssetWriterInput?
        if audioOut != nil {
            let input = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: audioSampleRate,
                AVNumberOfChannelsKey: 2,
                AVEncoderBitRateKey: audioBitRate,
            ])
            input.expectsMediaDataInRealTime = false
            if writer.canAdd(input) { writer.add(input); audioIn = input }
        }

        guard reader.startReading() else {
            throw TranscodeError.readerFailed(reader.error?.localizedDescription ?? "startReading")
        }
        guard writer.startWriting() else {
            reader.cancelReading()
            throw TranscodeError.writerFailed(writer.error?.localizedDescription ?? "startWriting")
        }
        writer.startSession(atSourceTime: .zero)

        await withTaskGroup(of: Void.self) { group in
            group.addTask { await pump(input: videoIn, output: videoOut, label: "oshi.chatvideo.v") }
            if let audioIn, let audioOut {
                group.addTask { await pump(input: audioIn, output: audioOut, label: "oshi.chatvideo.a") }
            }
        }

        if reader.status == .failed {
            writer.cancelWriting()
            try? FileManager.default.removeItem(at: output)
            throw TranscodeError.readerFailed(reader.error?.localizedDescription ?? "read")
        }
        await writer.finishWriting()
        guard writer.status == .completed else {
            try? FileManager.default.removeItem(at: output)
            throw TranscodeError.writerFailed(writer.error?.localizedDescription ?? "finish")
        }
        return plan
    }

    // MARK: - Internals

    /// Rotates and scales the source frames so they fill `renderSize` the right
    /// way up, preserving aspect ratio.
    private static func fitTransform(naturalSize: CGSize,
                                     preferredTransform: CGAffineTransform,
                                     renderSize: CGSize) -> CGAffineTransform {
        // Where the rotated frame lands, and how far it is from the origin.
        let rotated = CGRect(origin: .zero, size: naturalSize).applying(preferredTransform)
        guard rotated.width > 0, rotated.height > 0 else { return preferredTransform }
        let scale = min(renderSize.width / rotated.width, renderSize.height / rotated.height)
        // Rotation can push the frame into negative coordinates; translate it
        // back to the origin BEFORE scaling, then centre what is left over.
        let originFix = CGAffineTransform(translationX: -rotated.minX, y: -rotated.minY)
        let scaling = CGAffineTransform(scaleX: scale, y: scale)
        let centred = CGAffineTransform(
            translationX: (renderSize.width - rotated.width * scale) / 2,
            y: (renderSize.height - rotated.height * scale) / 2)
        return preferredTransform
            .concatenating(originFix)
            .concatenating(scaling)
            .concatenating(centred)
    }

    private static func pump(input: AVAssetWriterInput,
                             output: AVAssetReaderOutput,
                             label: String) async {
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            let queue = DispatchQueue(label: label)
            var finished = false
            input.requestMediaDataWhenReady(on: queue) {
                while input.isReadyForMoreMediaData && !finished {
                    if let buffer = output.copyNextSampleBuffer() {
                        if !input.append(buffer) { finished = true }
                    } else {
                        finished = true
                    }
                }
                if finished {
                    input.markAsFinished()
                    cont.resume()
                }
            }
        }
    }
}
