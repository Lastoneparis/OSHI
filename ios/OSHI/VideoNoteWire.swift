//
//  VideoNoteWire.swift
//  OSHI
//
//  __VIDEO_NOTE_2026_09_24__ The Foundation-only half of the video-note feature:
//  limits, the wire metadata (`videoNote` / `durationMs`, docs/VIDEO_NOTE_SPEC.md)
//  and the pure state rules. Compiled into the app AND the share extension,
//  because `V2MessageRouter.swift` (shared) builds the v2 file key with it.
//

import Foundation

// MARK: - Limits

enum VideoNote {
    /// Hard limit of a recording. The recorder stops by itself at exactly this.
    static let maxDuration: TimeInterval = 15.0
    /// Same limit on the wire, in milliseconds. Receivers clamp to it.
    static let hardCapMs = 15_000
    /// A clip whose ENCODED duration exceeds the limit by more than this is
    /// refused at send time (container rounding gives a few frames of slack).
    static let sendSlack: TimeInterval = 0.5
    /// Shortest clip worth sending — a tap-tap by accident is not a message.
    static let minDuration: TimeInterval = 0.5

    /// Square output side, in pixels (spec: ≤ 480 × 480).
    static let dimension = 480
    static let frameRate = 30
    /// 1.2 Mbit/s video + 64 kbit/s audio ≈ 2.4 MB for 15 s (spec: ≤ ~3 MB).
    static let videoBitRate = 1_200_000
    static let audioBitRate = 64_000
    static let audioSampleRate: Double = 44_100

    static let mime = "video/mp4"
    static let wireKeyVideoNote = "videoNote"
    static let wireKeyDurationMs = "durationMs"

    /// `videonote_<unix-millis>.mp4` — informative only, receivers never rely on it.
    static func fileName(now: Date = Date()) -> String {
        "videonote_\(Int64((now.timeIntervalSince1970 * 1000).rounded())).mp4"
    }

    /// Upper bound of the encoded size for `seconds` of recording, container
    /// overhead included. Used by tests to hold the ≤ 3 MB promise.
    static func estimatedMaxBytes(seconds: TimeInterval) -> Int {
        let bits = Double(videoBitRate + audioBitRate) * min(max(seconds, 0), maxDuration)
        return Int((bits / 8.0) * 1.05) + 16_384
    }

    /// `0:12`-style label.
    static func formatted(ms: Int) -> String {
        let total = max(0, Int((Double(ms) / 1000.0).rounded()))
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}

// MARK: - Wire metadata

/// What a message says about being a video note. `nil` everywhere else — the
/// type exists only for notes, so "not a note" is simply the absence of a value.
struct VideoNoteMetadata: Equatable, Sendable, Codable {
    /// Recorded duration in milliseconds, clamped to `[0, 15000]`; nil = unknown.
    let durationMs: Int?

    init(durationMs: Int?) {
        self.durationMs = VideoNoteMetadata.sanitize(durationMs)
    }

    var durationSeconds: TimeInterval? {
        durationMs.map { TimeInterval($0) / 1000.0 }
    }

    /// Negative → unknown; above the cap → the cap.
    static func sanitize(_ ms: Int?) -> Int? {
        guard let ms, ms >= 0 else { return nil }
        return min(ms, VideoNote.hardCapMs)
    }

    /// Tolerant reading of a JSON `durationMs` (integer, float, numeric NSNumber).
    /// A boolean, a string or anything else is "unknown", never an error.
    static func durationMs(fromJSON value: Any?) -> Int? {
        guard let number = value as? NSNumber else { return nil }
        // JSONSerialization hands `true` back as an NSNumber too.
        if CFGetTypeID(number) == CFBooleanGetTypeID() { return nil }
        let double = number.doubleValue
        guard double.isFinite else { return nil }
        return sanitize(Int(double.rounded()))
    }

    /// Reads the flag. Only an explicit JSON `true` counts.
    static func flag(fromJSON value: Any?) -> Bool {
        guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else {
            return false
        }
        return number.boolValue
    }

    /// Spec §1 receiver rule: a note only when flagged AND the media is a video.
    static func from(flag: Bool?, durationMs: Int?, isVideo: Bool) -> VideoNoteMetadata? {
        guard flag == true, isVideo else { return nil }
        return VideoNoteMetadata(durationMs: durationMs)
    }

    /// Decodes the two keys from any of the JSON objects listed in spec §2.
    static func parse(_ dict: [String: Any], isVideo: Bool) -> VideoNoteMetadata? {
        from(flag: flag(fromJSON: dict[VideoNote.wireKeyVideoNote]),
             durationMs: durationMs(fromJSON: dict[VideoNote.wireKeyDurationMs]),
             isVideo: isVideo)
    }

    /// Writes the two keys into a legacy media / mesh JSON dictionary.
    /// `false` is never written: the key is simply absent on a normal video.
    func apply(to dict: inout [String: Any]) {
        dict[VideoNote.wireKeyVideoNote] = true
        if let durationMs { dict[VideoNote.wireKeyDurationMs] = durationMs }
    }

    /// Is this media type string (either 1:1 or group spelling, any case) a video?
    static func isVideoType(_ raw: String?, mime: String? = nil) -> Bool {
        if raw?.lowercased() == "video" { return true }
        return mime?.lowercased().hasPrefix("video/") == true
    }
}

// MARK: - Composer chrome

/// The composer's one rule: the camera and video-note buttons stand beside an
/// EMPTY field, and fold away the moment there is something to send so the
/// text gets their width. Pure, so the state machine is unit-tested.
enum ComposerChrome {
    static func showsCaptureButtons(text: String) -> Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    static func isExpanded(text: String) -> Bool { !showsCaptureButtons(text: text) }

    /// A few more lines once the field owns the row.
    static func lineLimit(expanded: Bool) -> ClosedRange<Int> { expanded ? 1...8 : 1...5 }
}

// MARK: - 15 s clock

enum VideoNoteRecordingClock {
    /// Ring fill, 0…1.
    static func progress(elapsed: TimeInterval) -> Double {
        guard elapsed.isFinite, elapsed > 0 else { return 0 }
        return min(elapsed / VideoNote.maxDuration, 1)
    }

    static func shouldAutoStop(elapsed: TimeInterval) -> Bool {
        elapsed >= VideoNote.maxDuration
    }

    static func remaining(elapsed: TimeInterval) -> TimeInterval {
        max(0, VideoNote.maxDuration - max(0, elapsed))
    }

    /// Wire duration for a recorded length: rounded, never above the cap.
    static func durationMs(forRecorded seconds: TimeInterval) -> Int {
        guard seconds.isFinite, seconds > 0 else { return 0 }
        return min(Int((seconds * 1000).rounded()), VideoNote.hardCapMs)
    }

    /// The send-time gate on the ENCODED clip.
    static func acceptsForSend(duration seconds: TimeInterval) -> Bool {
        seconds.isFinite
            && seconds >= VideoNote.minDuration
            && seconds <= VideoNote.maxDuration + VideoNote.sendSlack
    }
}
