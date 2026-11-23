import Foundation
@preconcurrency import AVFoundation
import AVKit
import VideoToolbox
import CoreImage
import UIKit
import SwiftUI
import CryptoKit
import os.lock
import Network

// MARK: - Video Quality Presets
// 📐 Portrait 9:16 — video is always captured & encoded in portrait orientation.
// Camera preset .hd1280x720 gives native 16:9 (720x1280 in portrait).
// Encoder scales to the resolution below, preserving 9:16 aspect ratio.
enum VideoQuality: String, CaseIterable, Sendable {
    case low = "Low"      // 270x480 (9:16 portrait)
    case medium = "Medium" // 360x640 (9:16 portrait)
    case high = "High"    // 720x1280 (9:16 portrait HD)

    var resolution: CGSize {
        switch self {
        case .low: return CGSize(width: 270, height: 480)
        case .medium: return CGSize(width: 360, height: 640)
        case .high: return CGSize(width: 720, height: 1280)
        }
    }

    var bitrate: Int {
        switch self {
        // Bumped to match the Android side (1.2 Mbps medium / 2.5 Mbps high) so
        // both ends produce clean, comparable image at the same resolution. The
        // old 800k / 1.5M values caused visible pixelation on motion at HIGH.
        case .low: return 600_000       // 600 kbps
        case .medium: return 1_200_000  // 1.2 Mbps
        case .high: return 2_500_000    // 2.5 Mbps
        }
    }

    var frameRate: Int { return 30 }
}

// MARK: - Video Blur Mode
// MARK: - Media-channel video control (__VIDEO_MEDIA_CONTROL_2026_09_23__)

/// The 9-byte cleartext video controls Android and the desktop put on the MEDIA channel:
/// `[type][timestamp ms BE 8]` — `0x0B` keyframe request, `0x0C` camera paused, `0x0D`
/// camera resumed (`EnhancedCallManager.kt` `sendKeyframeRequest` / `sendVideoToggleSignal`,
/// desktop `VideoControl.encodeToggle`).
///
/// Up to b145 `receiveAudio` refused every datagram of ≤ 9 bytes (`guard count > 9`) and
/// counted it as a LOST AUDIO PACKET: a desktop's keyframe request never reached the
/// encoder, and each one nudged the audio bitrate down. Pure, so it is unit-tested.
enum VideoMediaControl: Equatable {
    case keyframeRequest
    case cameraPaused
    case cameraResumed

    /// Classify a media datagram. nil = not a video control (audio, in-band hang-up…).
    ///
    /// Only the exact 9-byte shape is a control: a longer `0x0D` is the sealed in-band
    /// callEnd, and nothing longer is ever sent for `0x0B`/`0x0C`.
    static func classify(_ data: Data) -> VideoMediaControl? {
        guard data.count == 9, let t = data.first else { return nil }
        switch t {
        case 0x0B: return .keyframeRequest
        case 0x0C: return .cameraPaused
        case 0x0D: return .cameraResumed
        default: return nil
        }
    }
}

/// __VIDEO_ABR_2026_09_23__ Inbound keyframe requests, as the encoder sees them.
///
/// - `admit`: force an IDR at most every `minInterval` (0.5 s ≈ 2/s, the same on Android
///   and the desktop). One IDR answers every request raised while it is in flight, and an
///   unauthenticated cleartext `0x0B` flood can no longer pin the encoder at keyframes.
/// - `pressureFactor`: the peer's requests are the ONLY report any client gets about ITS
///   uplink (no receiver reports exist), so they feed the bitrate adapter:
///   each request in the last 3 s costs 8 %, floor 0.35; the first 3 s of video are
///   decoder warm-up and do not count. Same constants as `VideoRateController.kt`.
struct PeerKeyframeRequests {
    var minInterval: TimeInterval = 0.5
    var window: TimeInterval = 3.0
    var weight: Double = 0.08
    var floor: Double = 0.35
    var startupGrace: TimeInterval = 3.0

    private(set) var lastAdmitted: Date = .distantPast
    private(set) var received: Int = 0
    private(set) var admitted: Int = 0
    private var recent: [Date] = []
    var startedAt: Date = Date()

    /// Record one request; true when the encoder should emit an IDR for it.
    mutating func note(at now: Date = Date()) -> Bool {
        received += 1
        if now.timeIntervalSince(startedAt) >= startupGrace {
            recent.append(now)
        }
        guard now.timeIntervalSince(lastAdmitted) >= minInterval else { return false }
        lastAdmitted = now
        admitted += 1
        return true
    }

    /// 1.0 = no pressure; lower = the peer keeps losing our frames.
    mutating func pressureFactor(at now: Date = Date()) -> Double {
        recent.removeAll { now.timeIntervalSince($0) > window }
        return max(floor, 1.0 - weight * Double(recent.count))
    }
}

enum VideoBlurMode: String, CaseIterable, Sendable {
    case none = "None"
    case backgroundBlur = "Background"
    case fullBlur = "Full Blur"
    case pixelate = "Pixelate"
    case heavyPixelate = "Heavy Pixelate"  // Stronger pixelation
    case ultraPixelate = "Ultra Pixelate"  // Maximum privacy
    
    var displayName: String { rawValue }
    
    var icon: String {
        switch self {
        case .none: return "video"
        case .backgroundBlur: return "person.fill"
        case .fullBlur: return "aqi.medium"
        case .pixelate: return "square.grid.3x3"
        case .heavyPixelate: return "square.grid.2x2"
        case .ultraPixelate: return "square.fill"
        }
    }
}

// MARK: - Video Jitter Buffer — DELETED (2026-07-27)
//
// 🔧 FIX: `VideoJitterBuffer` was a fully implemented reorder buffer with ZERO
// callers: `addPacket` was never invoked from anywhere, `getNextPacket` was only
// reachable from a playback timer that was never scheduled, and the decode path
// enqueues straight onto `displayLayer` (which does its own 1-frame-deep
// "flush if not ready, then enqueue" — see decodeFrame). Its presence made every
// reader assume incoming video was reordered/depth-buffered when it never was,
// and the call UI showed a "buf:N" chip fed by a counter nothing ever wrote.
// Removed rather than wired up: real-time video deliberately does NOT want to
// hold frames back (see the enqueue comment in decodeFrame), and reordering
// belongs at the FRAGMENT layer, which now has a real window (MAX_REASSEMBLY_ENTRIES).

// MARK: - Video Call Manager
@MainActor
class VideoCallManager: NSObject, ObservableObject {
    // File logger for debugging (same as VoiceCallManager)
    nonisolated(unsafe) private let fileLog = CallFileLogger.shared

    // 🔧 FIX: Static reference to the last active capture session.
    // When a second call starts, the new VideoCallManager instance can forcefully stop
    // the old capture session before trying to acquire the camera. This works around
    // ARC timing delays where the old VideoCallManager hasn't been deallocated yet
    // and its deinit hasn't released the camera.
    nonisolated(unsafe) private static var lastActiveCaptureSession: AVCaptureSession?

    // MARK: - Published Properties
    @Published var isVideoEnabled = false
    @Published var isUsingFrontCamera = true
    @Published var currentQuality: VideoQuality = .low
    @Published var blurMode: VideoBlurMode = .none
    @Published var blurIntensity: Double = 0.5
    @Published var localVideoImage: CGImage?
    @Published var outgoingBandwidth: Double = 0
    @Published var incomingBandwidth: Double = 0
    @Published var isReceivingVideo = false

    /// Decoded remote frames per second, refreshed once a second.
    ///
    /// 🔧 (2026-08-20) `isReceivingVideo` flips true on the FIRST packet and never
    /// flips back, so the UI could only ever say "there is a stream" — never "and
    /// it is 0.6 frames per second". On the 2026-08-20 call the peer delivered 146
    /// decodable frames in 255 seconds; the screen showed a still image with no
    /// explanation, and the call was reported as "I never saw his video".
    @Published var remoteVideoFPS: Double = 0
    /// No decoded remote frame for `remoteStallSeconds`.
    @Published var isRemoteVideoStalled = false
    /// __PEER_VIDEO_NEVER_ARRIVED_2026_08_26__ Not one frame has EVER been
    /// decoded, and we have waited long enough that "connecting" has stopped
    /// being an honest description.
    ///
    /// This state had no name, and that is exactly why it was invisible.
    /// `isRemoteVideoStalled` deliberately stays false when `lastRemoteFrameAt`
    /// is nil — the comment in `tickRemoteHealth` says so and the reasoning was
    /// sound for the case it was written for. But it left the WORST case with no
    /// treatment at all: nothing arrives, ever, and the screen shows the peer's
    /// avatar under "waiting for video" for the entire call, with no badge, no
    /// log line, and — the part that actually mattered — no further keyframe
    /// request after the three fired in the first 2.3 seconds.
    ///
    /// Reported twice now, most recently 2026-08-26: "je ne voyais pas Zak mais
    /// Zak me voyait". A peer whose camera takes longer than 2.3 s to produce
    /// its first frame (an answer from the background, a capture session
    /// restarting, a build without the capture-restart watchdog) was PERMANENTLY
    /// invisible, because the only thing that would have fixed it — asking again
    /// for a keyframe — is driven from the receive path, and the receive path
    /// never ran.
    @Published var isRemoteVideoNeverArrived = false

    // 🔧 NEW: Video settings
    @Published var isSendingVideo = true       // Enable/disable sending video
    @Published var isReceivingEnabled = true   // Enable/disable receiving video
    @Published var isPiPActive = false

    // ────────────────────────────────────────────────────────────────────────
    // 📡 Adaptive bitrate (2026-06-08, Bug B fix)
    //
    // Hugo's WiFi→5G handoff during call C30683BD made Natalia's video lag
    // because the encoder kept pushing the configured "high" bitrate
    // (2.5 Mbps) into a congested WS path that was buffering frames.
    //
    // Mechanism:
    //   - NWPathMonitor watches the network class (wifi / cellular /
    //     constrained / expensive)
    //   - A 2 Hz adapter timer multiplies `currentQuality.bitrate` by a
    //     conservative factor for non-WiFi paths and calls
    //     `applyAdaptiveBitrate(kbps:)` which patches the LIVE VTSession
    //     without recreating it
    //   - `currentBitrateMultiplier` is `@Published` so the call UI can show
    //     "Auto • 720k" or similar if we ever want to surface it
    @Published var currentBitrateKbps: Int = 0
    @Published var currentNetworkClass: String = "unknown"
    // nonisolated(unsafe): NWPathMonitor's pathUpdateHandler is a Sendable closure
    // that fires on `pathMonitorQueue`, so these flags MUST be writable off the
    // main actor. They're only ever written from the monitor callback and read
    // from `tickAdaptiveBitrate` (main actor); benign race since the timer
    // fires 2 Hz and a single observation lag is harmless.
    nonisolated(unsafe) private var pathMonitor: NWPathMonitor?
    private let pathMonitorQueue = DispatchQueue(label: "com.oshi.video.path", qos: .utility)
    private var bitrateAdapterTimer: Timer?
    private var lastAppliedBitrate: Int = 0
    private var adaptiveBitrateMultiplier: Double = 1.0
    /// __VIDEO_ABR_2026_09_23__ Peer keyframe requests: IDR gate (~2/s) + uplink pressure.
    private var peerKeyframeRequests = PeerKeyframeRequests()
    nonisolated(unsafe) private var isOnExpensivePath: Bool = false
    nonisolated(unsafe) private var isOnConstrainedPath: Bool = false
    nonisolated(unsafe) private var pathUsesCellular: Bool = false
    nonisolated(unsafe) private var pathUsesWifi: Bool = false

    // 🛡️ AUDIO-PRIORITY: video is the shock absorber on a thin link.
    // When the audio path (the ONLY closed loop fed by REAL measured loss+RTT,
    // owned by VoiceCallManager) reports it's stressed, we FREEZE outgoing
    // video — drop P-frames, ship only a periodic keyframe so the far end keeps
    // a still last-frame instead of a black tile — handing the whole pipe to
    // voice. Audio never yields; it keeps its protected floor + FEC.
    //
    // nonisolated(unsafe): read from the nonisolated encode/send callback
    // (sendVideoPacket runs on VT's thread); written only from the 2 Hz
    // MainActor tick. Benign single-observation race like the path flags above.
    nonisolated(unsafe) private var isVideoFrozenForAudio: Bool = false
    // While frozen, still emit one keyframe every ~2 s so a receiver that joined
    // (or lost sync) during the freeze can paint a fresh still rather than black.
    nonisolated(unsafe) private var lastFrozenKeyframeSentAt: Date = .distantPast
    private let frozenKeyframeIntervalSeconds: TimeInterval = 2.0

    /// A remote stream is "stalled" after this long with no decoded frame. Three
    /// seconds is deliberately longer than `frozenKeyframeIntervalSeconds` (2 s):
    /// a peer that has frozen its video to protect audio still trickles one
    /// keyframe every two seconds, and that is a REAL, if terrible, stream — it
    /// must read as "very weak", not as "stalled".
    private let remoteStallSeconds: TimeInterval = 3.0
    /// Below this the picture is not a video any more; say so.
    private let remoteWeakFPS: Double = 4.0
    /// __PEER_VIDEO_NEVER_ARRIVED_2026_08_26__ How long a video call may show
    /// "waiting for video" before that stops being true and starts being a
    /// silence. Six seconds is past the last of the three startup keyframe
    /// requests (2.3 s) with room for a slow camera and one network round trip,
    /// so it cannot fire on a call that is merely starting up.
    private let remoteNeverArrivedSeconds: TimeInterval = 6.0
    /// Re-ask for a keyframe every N ticks (the monitor ticks once a second)
    /// while nothing has ever arrived. Slow on purpose: the peer may simply have
    /// no camera running, and a PLI storm would not create one.
    private let neverArrivedKeyframeEveryTicks: Int = 3
    private var remoteHealthTimer: Timer?
    private var lastRemoteFrameAt: Date?
    /// When the monitor started — the clock the never-arrived escalation is
    /// measured against. `lastRemoteFrameAt` cannot serve: it is nil in exactly
    /// the case we need to time.
    private var remoteHealthStartedAt: Date?
    private var neverArrivedTicks: Int = 0

    // 📺 PiP — AVSampleBufferDisplayLayer for native iOS PiP
    // The layer must be in the actual view hierarchy for PiP to work.
    // nonisolated(unsafe) because enqueue() is called from VT decode callback (background thread).
    // AVSampleBufferDisplayLayer.enqueue() is thread-safe.
    nonisolated(unsafe) let displayLayer = AVSampleBufferDisplayLayer()
    private var pipController: AVPictureInPictureController?
    private var pipPlaybackDelegate: PiPPlaybackDelegate?

    // 📺 iOS 15+ VIDEO-CALL PiP (2026-09-17). The old path used the sample-buffer
    // PiP (`ContentSource(sampleBufferDisplayLayer:playbackDelegate:)`), which iOS
    // ALWAYS renders with a media play/pause control — so the call PiP "looked like
    // a player." `AVPictureInPictureVideoCallViewController` is the API built for
    // VoIP: a clean PiP with NO playback chrome (like FaceTime). `displayLayer`
    // lives inside this VC's view; AVKit lifts the WHOLE VC view into the PiP
    // window (it never manually reparents the CALayer, so the teardownPiP
    // layer-move crash — OSHI-2026-08-20 — cannot recur). `pipSourceView` is the
    // on-screen container (set by RemoteVideoLayerView) the PiP animates from.
    // Guarded by `oshi.pip.videoCall` (default ON) so it can be flipped back to the
    // old sample-buffer PiP from UserDefaults without a rebuild if a device regresses.
    nonisolated(unsafe) weak var pipSourceView: UIView?
    private var pipVideoCallVC: AVPictureInPictureVideoCallViewController?
    private var pipUsesVideoCall = false
    var useVideoCallPiP: Bool {
        (UserDefaults.standard.object(forKey: "oshi.pip.videoCall") as? Bool) ?? true
    }

    /// Lazily builds the video-call PiP content VC and parks `displayLayer` inside
    /// it. Reused for the life of the manager; the layer is placed exactly once.
    func ensurePiPVideoCallController() -> AVPictureInPictureVideoCallViewController {
        if let vc = pipVideoCallVC { return vc }
        let vc = AVPictureInPictureVideoCallViewController()
        // Portrait-ish default; AVKit uses videoGravity/aspect for the real ratio.
        vc.preferredContentSize = CGSize(width: 9, height: 16)
        vc.view.backgroundColor = .black
        let host = DisplayLayerHostView(displayLayer: displayLayer)
        host.translatesAutoresizingMaskIntoConstraints = false
        vc.view.addSubview(host)
        NSLayoutConstraint.activate([
            host.leadingAnchor.constraint(equalTo: vc.view.leadingAnchor),
            host.trailingAnchor.constraint(equalTo: vc.view.trailingAnchor),
            host.topAnchor.constraint(equalTo: vc.view.topAnchor),
            host.bottomAnchor.constraint(equalTo: vc.view.bottomAnchor)
        ])
        pipVideoCallVC = vc
        return vc
    }

    // Last rotation hint received from the peer (bits 1-2 of the video flags
    // byte: 0/1/2/3 → 0°/90°/180°/270°). Used to rotate Android peers' video
    // upright when the encoder sends sensor-orientation frames.
    // nonisolated because the parser runs off the main actor.
    nonisolated(unsafe) private var lastRemoteRotationCode: Int = 0

    // 🔧 Wire rotation hint for the next outgoing encoded frame. Set in
    // encodeFrame from the INPUT pixel buffer's dimensions (which expose
    // whether AVCaptureConnection.videoRotationAngle actually applied),
    // read in handleEncodedFrame because the encoder squashes the buffer
    // to its configured 360x640 portrait dimensions regardless of input.
    // Safe to share because we disabled frame reordering and B-frames:
    // VT emits frames in input order, so the most recent input's
    // rotation matches the most recent output.
    nonisolated(unsafe) private var pendingWireRotationCode: UInt8 = 0

    // MARK: - Private Properties
    private var captureSession: AVCaptureSession?
    private var videoOutput: AVCaptureVideoDataOutput?
    private var currentCameraInput: AVCaptureDeviceInput?
    private let videoQueue = DispatchQueue(label: "com.oshi.video.capture", qos: .userInteractive)
    /// Serial queue owning EVERY AVCaptureSession mutation: begin/commitConfiguration,
    /// addInput/addOutput, startRunning, stopRunning.
    ///
    /// 🔧 FIX (main-thread stall, same defect as QRScannerView in 16be239):
    /// VideoCallManager is @MainActor, so `setupCaptureSession()` and `switchCamera()`
    /// ran the whole configuration — device discovery, AVCaptureDeviceInput creation,
    /// begin/addInput/addOutput/commitConfiguration, and a SYNCHRONOUS
    /// `previousSession.stopRunning()` — on the runloop. That is ~0.3–1 s of blocking
    /// hardware round-trips at the exact moment the call UI is being presented.
    ///
    /// It is also deliberately NOT `videoQueue`: `videoQueue` is the sample-buffer
    /// delegate queue, and `stopRunning()` blocks until in-flight delegate callbacks
    /// drain — issuing it from the delegate queue itself is the re-entrant shape that
    /// commit 16be239 called out. All start/stop was previously flung at `videoQueue`
    /// (or `DispatchQueue.global()` in deinit), so a stop could also overtake the start
    /// it was meant to cancel; one serial queue removes that race too.
    private let sessionQueue = DispatchQueue(label: "com.oshi.video.session", qos: .userInitiated)
    private let processingQueue = DispatchQueue(label: "com.oshi.video.processing", qos: .userInteractive)
    // 🔧 FIX: Dedicated GPU rendering queue — keeps ciContext.createCGImage OFF MainActor
    private let renderQueue = DispatchQueue(label: "com.oshi.video.render", qos: .userInitiated)
    // Frame throttle timestamps (nonisolated for access from background threads; benign race on Doubles)
    nonisolated(unsafe) private var lastRemoteRenderTime: CFAbsoluteTime = 0
    nonisolated(unsafe) private var lastLocalRenderTime: CFAbsoluteTime = 0

    // 📹 __LOCAL_CAPTURE_WATCHDOG_2026_08_22__ Nombre d'images SORTIES DE LA
    // CAMÉRA depuis le lancement de l'app. C'est la seule preuve que la capture
    // vit: `captureSession.isRunning` peut être vrai sans qu'aucune image
    // n'arrive, et `isVideoEnabled` est un drapeau que `startCapture()` posait
    // même quand la session n'avait jamais démarré. Voir `armLocalCaptureWatchdog`.
    nonisolated(unsafe) private var capturedFrameCount: Int = 0
    /// Surveillance du démarrage caméra: relance la capture quand aucune image
    /// n'est arrivée. Annulée par tout arrêt volontaire.
    private var localCaptureWatchdog: Task<Void, Never>?
    /// Garde de ré-entrance de `startVideo()`. `isVideoEnabled` ne peut pas la
    /// tenir: il reste FAUX pendant toute la configuration asynchrone.
    private var isStartingVideo = false
    // 🔧 FIX: Atomic flags readable from capture/encode callbacks (background queues)
    nonisolated(unsafe) private var isStopped: Bool = false
    nonisolated(unsafe) private var isSendingEnabled: Bool = true
    nonisolated(unsafe) private var currentBlurMode: VideoBlurMode = .none
    nonisolated(unsafe) private var currentBlurIntensity: Double = 0.5
    // 🔧 FIX (iPhone 14 receives iPhone 17's video 90° rotated): the wire
    // rotation code is computed in the nonisolated encode callback, which
    // can't touch the MainActor @Published isUsingFrontCamera. Mirror it
    // here and keep it in sync from startVideo / switchCamera.
    nonisolated(unsafe) private var isUsingFrontCameraMirror: Bool = true

    // Encoding — nonisolated(unsafe) so encode path can run on videoQueue without MainActor
    // Access is serialized: writes in setupEncoder/stopVideo (MainActor), reads in encodeFrame (videoQueue)
    nonisolated(unsafe) private var compressionSession: VTCompressionSession?
    nonisolated(unsafe) private var decompressionSession: VTDecompressionSession?
    nonisolated(unsafe) private var decoderFormatDescription: CMVideoFormatDescription?
    nonisolated(unsafe) private var frameCount: UInt64 = 0
    // 🔧 FIX: Initialize to distantPast so first frame is forced as keyframe
    nonisolated(unsafe) private var lastKeyFrameTime: Date = Date.distantPast
    // 🔧 ROBUST: Cache SPS/PPS from format description (sender side)
    nonisolated(unsafe) private var cachedSPS: Data?
    nonisolated(unsafe) private var cachedPPS: Data?
    // 🔧 Cache SPS/PPS received from remote (receiver side)
    nonisolated(unsafe) private var receivedCachedSPS: Data?
    nonisolated(unsafe) private var receivedCachedPPS: Data?
    // 🔧 CRITICAL: Track whether we've received first IDR — can't decode P-frames without it
    nonisolated(unsafe) private var hasDecodedIDR: Bool = false

    // Encryption — nonisolated for access from encode path on videoQueue
    nonisolated(unsafe) private var sessionKey: Data?

    // ────────────────────────────────────────────────────────────────────────
    // 🔐 CRYPTO FIX (2026-07-27) — AES-GCM NONCE REUSE IN VIDEO
    //
    // What was broken: the nonce was built as [counter LE(8)][0x00 ×4]. No salt,
    // no direction bit, and `_videoNonce` was never reset. Both ends start at 0,
    // both increment by one per fragment, and both use the SAME AES key (the audio
    // session key, fetched via getSessionKeyFromVoiceCall). So the caller's video
    // fragment #1 and the callee's video fragment #1 carried a BIT-IDENTICAL nonce
    // under a BIT-IDENTICAL key. That is a total GCM break: XOR of two ciphertexts
    // under one nonce leaks the XOR of the plaintexts, and the GHASH authentication
    // subkey is recoverable from the pair → forgery. The E2EE claim was false for
    // video regardless of how strong the key exchange is.
    //
    // Fix (mirrors 2f25823's audio fix, but derived from state this file can reach
    // on its own — VoiceCallManager.txNonceSalt is private to that type):
    //
    //   nonce = [salt(4)][counter BE(8)]        ← same layout as the audio path
    //
    //   • salt bit 7 of byte 0 = DIRECTION BIT, taken from
    //     `voiceCallManager.isOutgoingCall`: caller ⇒ 1, callee ⇒ 0. Two devices in
    //     one call always disagree on this, so the two directions occupy provably
    //     DISJOINT nonce spaces — the cross-direction collision cannot occur even
    //     at identical counters.
    //   • the remaining 31 bits are fresh random per VIDEO SESSION (re-drawn on
    //     every startVideo/stopVideo cycle) so a voice→video→voice→video toggle
    //     inside one call — which resets the counter — cannot replay a nonce.
    //   • bit 63 of the counter field is FORCED SET. The audio path shares this key
    //     and its own [salt(4)][counter BE(8)] layout with a counter that starts at
    //     1 and realistically never passes 2^32, so forcing the top bit makes the
    //     audio and video nonce spaces disjoint by construction rather than by a
    //     2^-32 argument about salt collisions.
    //
    // WIRE COMPATIBILITY (verified, not assumed): `decryptPacket` below takes the
    // 12 nonce bytes straight off the wire, feeds them to AES.GCM.Nonce and never
    // compares them against any locally derived salt or counter. The Android client
    // parses the same [nonce(12)][ciphertext][tag(16)] envelope. Nothing on any
    // receiver derives meaning from the nonce BYTES, so changing what we transmit is
    // invisible to old peers — exactly the argument made for the audio fix.
    // (Old-peer→us still collides across directions; that half cannot be fixed
    // unilaterally without a negotiated capability flag.)
    // ────────────────────────────────────────────────────────────────────────
    nonisolated(unsafe) private var _videoNonce: UInt64 = 0
    /// TX salt for the CURRENT video session. nil ⇒ derive on next use. Guarded by `nonceLock`.
    nonisolated(unsafe) private var _videoTxSalt: Data?
    nonisolated(unsafe) private var nonceLock = os_unfair_lock()

    /// Top bit of the 8-byte counter field — marks the nonce as VIDEO so it can
    /// never alias a VoiceCallManager audio nonce built under the same key.
    nonisolated private static let VIDEO_NONCE_DOMAIN_BIT: UInt64 = 0x8000_0000_0000_0000

    /// MUST be called with `nonceLock` held. Derives (once per video session) the
    /// 4-byte transmit salt: 31 random bits + the direction bit in the MSB.
    nonisolated private func videoTxSaltLocked() -> Data {
        if let existing = _videoTxSalt { return existing }
        // SystemRandomNumberGenerator (what `UInt8.random` uses) is documented as
        // cryptographically secure on Apple platforms — no Security import needed.
        var salt = Data((0..<4).map { _ in UInt8.random(in: UInt8.min...UInt8.max) })
        // Direction bit. `isOutgoingCall` is a plain @Published Bool on a
        // @unchecked Sendable class, so it is readable from this nonisolated path
        // (getSessionKeyFromVoiceCall already reads VoiceCallManager the same way).
        let weAreCaller = voiceCallManager?.isOutgoingCall ?? false
        salt[salt.startIndex] = (salt[salt.startIndex] & 0x7F) | (weAreCaller ? 0x80 : 0x00)
        _videoTxSalt = salt
        let hex = salt.map { String(format: "%02x", $0) }.joined()
        fileLog.log("🔐 Video nonce salt: role=\(weAreCaller ? "caller" : "callee") tx=\(hex) (direction bit = MSB)")
        return salt
    }

    /// Thread-safe video nonce increment (nonisolated for encode path).
    /// Returns the full 12-byte nonce so salt + counter are produced atomically.
    nonisolated private func nextVideoNonceBytes() -> Data {
        os_unfair_lock_lock(&nonceLock)
        let salt = videoTxSaltLocked()
        _videoNonce += 1
        let counter = _videoNonce | VideoCallManager.VIDEO_NONCE_DOMAIN_BIT
        os_unfair_lock_unlock(&nonceLock)

        var nonce = Data(capacity: 12)
        nonce.append(salt)
        withUnsafeBytes(of: counter.bigEndian) { nonce.append(contentsOf: $0) }
        return nonce
    }

    /// Thread-safe nonce read (diagnostics only)
    nonisolated var videoTxNonceCount: UInt64 {
        os_unfair_lock_lock(&nonceLock)
        let result = _videoNonce
        os_unfair_lock_unlock(&nonceLock)
        return result
    }

    // ────────────────────────────────────────────────────────────────────────
    // 🔐 RX anti-replay. `decryptPacket` previously had NO replay check of any
    // kind: a captured fragment could be re-injected forever and would decrypt,
    // reassemble and decode every time.
    //
    // The check is a bounded set of recently-seen 12-byte nonces rather than a
    // counter high-water mark, deliberately: peers running the OLD build emit
    // [counter LE(8)][0000], whose bytes 4..11 are constant zero, so any
    // counter-based window would read every one of their packets as "counter 0"
    // and reject the entire stream after the first fragment. Comparing whole
    // nonces is layout-agnostic and therefore safe against every peer version.
    //
    // Checked AFTER the GCM tag verifies, so an attacker cannot poison the window
    // with forged nonces.
    // ────────────────────────────────────────────────────────────────────────
    nonisolated(unsafe) private var seenNonces: Set<Data> = []
    nonisolated(unsafe) private var seenNonceOrder: [Data] = []
    nonisolated(unsafe) private var replayDropCount: Int = 0
    nonisolated(unsafe) private var replayLock = os_unfair_lock()
    /// ~2 s of fragments at 30 fps × up to 16 fragments/frame.
    nonisolated private static let MAX_SEEN_NONCES: Int = 1024

    /// Escape hatch. Default TRUE (protection on). Residual risk this exists for:
    /// a peer running the OLD build that toggles video off→on inside one call
    /// restarts its counter at 0, so its first fragments can alias nonces still in
    /// our window and get dropped until the window rolls (≤1024 fragments). Set
    /// `oshi.video.replayProtection` = false to fall back to the old
    /// accept-everything behaviour if that is ever observed in the field.
    nonisolated private static let replayProtectionEnabled: Bool = {
        if UserDefaults.standard.object(forKey: "oshi.video.replayProtection") == nil { return true }
        return UserDefaults.standard.bool(forKey: "oshi.video.replayProtection")
    }()

    /// True when this nonce has already been accepted in this session.
    nonisolated private func isReplayedNonce(_ nonce: Data) -> Bool {
        os_unfair_lock_lock(&replayLock)
        defer { os_unfair_lock_unlock(&replayLock) }
        if seenNonces.contains(nonce) {
            replayDropCount += 1
            if replayDropCount <= 5 || replayDropCount % 200 == 0 {
                let m = "DIAG_VIDEO_REPLAY | dropped=\(replayDropCount) | nonce=\(nonce.prefix(4).map { String(format: "%02x", $0) }.joined())…"
                OshiLog.call.info("\(m)"); fileLog.log(m)
            }
            return true
        }
        seenNonces.insert(nonce)
        seenNonceOrder.append(nonce)
        if seenNonceOrder.count > VideoCallManager.MAX_SEEN_NONCES {
            let evicted = seenNonceOrder.removeFirst()
            seenNonces.remove(evicted)
        }
        return false
    }

    // ────────────────────────────────────────────────────────────────────────
    // 📹 Application-level video fragmentation (iOS↔Android cross-platform)
    //
    // Wire format of each fragment (after AES-GCM decrypt, inside 0xF1 envelope):
    //   byte 0       : 0x01                  — fragment magic
    //   bytes 1..2   : frame_id    (UInt16 BE)
    //   byte 3       : fragment_index (UInt8) 0..(total-1)
    //   byte 4       : total_fragments (UInt8) 1..255
    //   bytes 5..    : payload bytes for this fragment
    //
    // Budget (1000 NAL/payload + 5 frag header + 1 type + 8 seq + 12 nonce + 16
    // GCM tag = 1042B). ALL frames use this format (single-fragment frames
    // have total=1, idx=0).
    //
    // __VIDEO_MTU_2026_09_23__ 1100 → 1000 (sender-only: every receiver —
    // iOS, Android, desktop — concatenates whatever fragment sizes arrive, so no
    // peer needs an update). The old "1142 B, well under 1200 B" forgot the
    // carrier: the :8089 relay frame adds [t][len][recip][len][sender][len]
    // [callId] = 126-128 B, so a full fragment left as a 1270 B UDP payload —
    // 1318 B on IPv6, over the 1280 B minimum MTU ⇒ IP-fragmented and dropped
    // on VPN/5G, the same failure as the 1957 B raw-PCM audio. At 1000: ≤ 1170 B
    // relay-up, ≤ 1126 B relay-down (under the server's UDP_SAFE_DATAGRAM =
    // 1200, so one-way video is no longer mirrored over WS), ≤ 1218 B on IPv6.
    // ────────────────────────────────────────────────────────────────────────
    nonisolated static let VIDEO_FRAG_MAGIC: UInt8 = 0x01
    nonisolated static let VIDEO_FRAG_HEADER_SIZE: Int = 5
    nonisolated static let MAX_FRAGMENT_PAYLOAD_BYTES: Int = 1000
    nonisolated static let MAX_REASSEMBLY_ENTRIES: Int = 8

    nonisolated(unsafe) private var txFrameId: Int = 0  // UInt16 (0..65535)
    private final class FragReassembly {
        let total: Int
        let createdAtMs: Int64
        var fragments: [Data?]
        var receivedCount: Int = 0
        init(total: Int) {
            self.total = total
            self.createdAtMs = Int64(Date().timeIntervalSince1970 * 1000)
            self.fragments = Array(repeating: nil, count: total)
        }
    }
    nonisolated(unsafe) private var reassemblyMap: [Int: FragReassembly] = [:]
    nonisolated(unsafe) private var reassemblyOrder: [Int] = []  // insertion order, oldest first
    nonisolated(unsafe) private var rxLastCompletedFrameId: Int = -1
    nonisolated(unsafe) private var reassemblyLock = os_unfair_lock()
    nonisolated(unsafe) private var diagFragTxCount: Int = 0
    nonisolated(unsafe) private var diagFragRxCount: Int = 0
    // 📊 Frame-completion accounting — see noteFrameCompleted. Guarded by `reassemblyLock`.
    nonisolated(unsafe) private var rxCompletedFrames: Int = 0
    nonisolated(unsafe) private var rxLostFrames: Int = 0
    // 📊 TX diagnostics: measured keyframe size + IDR-request cadence.
    nonisolated(unsafe) private var diagLastKeyframeBytes: Int = 0
    nonisolated(unsafe) private var diagMaxKeyframeBytes: Int = 0
    nonisolated(unsafe) private var diagKeyframeRequestsSent: Int = 0
    nonisolated(unsafe) private var diagLastKeyframeRequestAt: Date = .distantPast
    nonisolated(unsafe) private var diagOversizeFramesDropped: Int = 0

    /// 🔧 FIX (fragment loss was NEVER detected): an incomplete frame returns early
    /// from receiveVideoPacket and never reaches decodeFrame, so
    /// `consecutiveDecodeFailures` never incremented and the PLI in the decode
    /// callback could not fire on fragment loss — the DOMINANT loss mode on UDP,
    /// where losing 1 of N fragments silently kills the whole frame. The only signal
    /// available is the sequence of COMPLETED frame ids: a gap means one or more
    /// frames never assembled. Call this at every completion site; it detects the
    /// gap, counts it, and asks the peer for a fresh IDR.
    ///
    /// MUST be called with `reassemblyLock` NOT held (it takes the lock itself, and
    /// os_unfair_lock is not recursive), and it calls out to VoiceCallManager.
    nonisolated private func noteFrameCompleted(_ frameId: Int) {
        var gap = 0
        os_unfair_lock_lock(&reassemblyLock)
        let previous = rxLastCompletedFrameId
        if previous >= 0 {
            // Circular distance, UInt16 wrap-safe. dist == 1 is the healthy case.
            let dist = (frameId - previous) & 0xFFFF
            if dist >= 1 && dist <= 32768 { gap = dist - 1 }
        }
        rxLastCompletedFrameId = frameId
        rxCompletedFrames += 1
        rxLostFrames += gap
        let completed = rxCompletedFrames
        let lost = rxLostFrames
        os_unfair_lock_unlock(&reassemblyLock)

        guard gap > 0 else { return }
        let pct = completed + lost > 0 ? (Double(completed) * 100.0 / Double(completed + lost)) : 100.0
        let m = "DIAG_VIDEO_FRAME_LOSS | gap=\(gap) | frameId=\(frameId) | prev=\(previous) | completed=\(completed) | lost=\(lost) | completionRate=\(String(format: "%.1f", pct))%"
        OshiLog.call.info("\(m)"); fileLog.log(m)
        requestKeyframeFromPeer()
    }

    // Bandwidth tracking
    nonisolated(unsafe) private var bytesSent: UInt64 = 0
    nonisolated(unsafe) private var bytesReceived: UInt64 = 0
    nonisolated(unsafe) private var lastBandwidthUpdate = Date()

    // CoreImage for effects
    private let ciContext = CIContext(options: [.useSoftwareRenderer: false])

    /// __PREVIEW_READBACK_2026_08_26__ Grand côté, en pixels, du `CGImage`
    /// rapatrié pour la vignette d'auto-aperçu.
    ///
    /// La vignette fait 90×160 POINTS dans `VideoCallView`, soit 270×480 pixels
    /// sur l'écran ×3 le plus dense qu'on livre. 512 laisse une marge et reste
    /// 6,3 fois moins de pixels que la trame de capture 720×1280.
    ///
    /// C'est la SEULE valeur qui décide du coût de ce rapatriement: le reste du
    /// pipeline (rotation, encodage, décodage) travaille toujours en pleine
    /// résolution et n'est pas touché.
    nonisolated static let previewTargetLongEdge: CGFloat = 512

    nonisolated(unsafe) private var diagPreviewRendered: UInt64 = 0
    nonisolated(unsafe) private var diagPreviewFailed: UInt64 = 0

    /// Réduction de l'image d'auto-aperçu AVANT le rapatriement GPU→CPU.
    ///
    /// Extraite pour être TESTÉE. Une homothétie CoreImage qui rendrait une
    /// étendue vide ou non entière donnerait un `createCGImage` nul, donc une
    /// vignette figée, SANS une ligne d'erreur — et c'est exactement ce qui a
    /// été signalé après que j'ai introduit cette réduction.
    ///
    /// L'étendue de sortie est ARRONDIE à l'entier: une étendue fractionnaire
    /// (1280 × 0,4 tombe juste, 1000 × 0,512 non) est le genre de détail qui ne
    /// casse que sur certaines résolutions de caméra.
    ///
    /// Jamais d'agrandissement (`min(1, …)`), et une étendue infinie ou nulle
    /// laisse l'image intacte plutôt que de produire un facteur zéro.
    nonisolated static func previewScaled(_ image: CIImage) -> CIImage {
        let extent = image.extent
        let longEdge = max(extent.width, extent.height)
        guard longEdge.isFinite, longEdge > 0, extent.width > 0, extent.height > 0 else { return image }
        let scale = min(1.0, previewTargetLongEdge / longEdge)
        guard scale < 1.0 else { return image }
        let scaled = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        // `integral` élargit au pixel entier le plus proche vers l'extérieur.
        let snapped = scaled.extent.integral
        guard snapped.width >= 1, snapped.height >= 1 else { return image }
        return scaled.cropped(to: snapped)
    }

    // 📐 Orientation tracking — dynamically follows device rotation
    // Uses videoRotationAngle (iOS 17+) instead of deprecated AVCaptureVideoOrientation
    private var orientationObserver: NSObjectProtocol?
    nonisolated(unsafe) private var currentRotationAngle: CGFloat = 90  // 90 = portrait
    
    // Callbacks
    nonisolated(unsafe) var onVideoPacketReady: ((Data) -> Void)?
    var onError: ((String) -> Void)?
    
    // Reference to VoiceCallManager for session key
    nonisolated(unsafe) weak var voiceCallManager: VoiceCallManager?
    
    // MARK: - Initialization
    override init() {
        super.init()
        let addr = Unmanaged.passUnretained(self).toOpaque()
        OshiLog.call.info("📹 VideoCallManager: INIT (\(addr))")
        fileLog.log("📹 VideoCallManager: INIT (\(addr))")

        // Listen for keyframe requests from peer (via VoiceCallManager signal handling)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleForceKeyframeNotification),
            name: NSNotification.Name("ForceVideoKeyframe"),
            object: nil
        )

        // 📹 FIX: Listen for call end — stops video immediately without waiting for SwiftUI state change
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleCallEndNotification),
            name: NSNotification.Name("StopVideoCall"),
            object: nil
        )

        // 📹 Mid-call revert (voice ↔ video toggle). Same camera-teardown path
        // as call-end, but VideoCallView doesn't show the "Call Ended" overlay
        // for this — see VideoCallView.onReceive("StopVideoMidCall").
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleStopVideoMidCallNotification),
            name: NSNotification.Name("StopVideoMidCall"),
            object: nil
        )

        // 📹 Flush the display layer when the app foregrounds mid-call.
        // VoiceCallManager.handleAppDidBecomeActive sends this after locking +
        // unlocking the screen so the queued backlog doesn't play in a burst.
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleFlushVideoBuffersNotification),
            name: NSNotification.Name("FlushVideoDisplayBuffers"),
            object: nil
        )
    }

    @objc private func handleFlushVideoBuffersNotification() {
        fileLog.log("📹 VideoCallManager: flushing display layer (app foregrounded mid-call)")
        displayLayer.flush()
    }

    @objc private func handleCallEndNotification() {
        fileLog.log("📹 VideoCallManager: received StopVideoCall notification — stopping")
        stopVideo()
    }

    @objc private func handleStopVideoMidCallNotification() {
        fileLog.log("📹 VideoCallManager: received StopVideoMidCall notification — stopping camera (call stays alive)")
        stopVideo()

        // 🔴 __PEER_VIDEO_WENT_BLIND_2026_08_25__ Rouvrir la porte de RÉCEPTION.
        //
        // `stopVideo()` met `receivingEnabledMirror = false`, et c'est la toute
        // PREMIÈRE ligne de `receiveVideoPacket` qui la lit — avant le compteur,
        // avant la moindre trace. Donc à partir de là, chaque paquet vidéo du
        // pair est jeté EN SILENCE. Pas une ligne de log, pas un compteur qui
        // bouge: c'est ce qui rend ce défaut invisible.
        //
        // Le rapport d'appel du 2026-08-24 le montre exactement: 7 238 trames
        // vidéo émises, ZÉRO reçue, et un `stopVideo` à 22:11:14 suivi de cinq
        // minutes d'appel pendant lesquelles la vidéo du pair ne POUVAIT pas
        // s'afficher. « La vidéo de Zak ne s'affiche pas. »
        //
        // Couper SA PROPRE caméra et refuser celle de l'autre sont deux choses
        // différentes. Le `= false` a été ajouté le 2026-08-20 pour fermer une
        // course au crash (les trames en vol du pair continuaient d'atteindre
        // `VTDecompressionSessionDecodeFrame` pendant qu'on démontait le
        // décodeur autour d'elles). Ce correctif-là était juste — mais il a
        // confondu « je démonte ma capture » avec « je n'accepte plus rien ».
        //
        // La porte reste donc fermée pendant TOUT le démontage, ce qui est ce
        // qui la rend sûre, et ne se rouvre qu'ici: le chemin qui dit
        // explicitement « call stays alive ». Le chemin de fin d'appel
        // (`handleCallEndNotification`) ne passe pas par là et garde la porte
        // fermée.
        receivingEnabledMirror = true
        isReceivingEnabled = true
        fileLog.log("📹 VideoCallManager: RX rouverte après arrêt caméra — réception seule")

        // Le décodeur vient d'être invalidé et les SPS/PPS effacés: sans image
        // clé, les trames qui arrivent ne peuvent rien produire. On en demande
        // une. (`sendKeyframeRequest` limite déjà à une toutes les 2 s.)
        requestKeyframeFromPeer()
    }

    @objc private func handleForceKeyframeNotification() {
        // __VIDEO_ABR_2026_09_23__ The PEER asked (signal lane, or — since this build —
        // the 9-byte media-channel 0x0B from Android / desktop). At most one IDR per
        // 0.5 s, and every request counts as pressure on our uplink for the adapter.
        guard peerKeyframeRequests.note() else {
            if peerKeyframeRequests.received % 20 == 0 {
                fileLog.log("📹 ForceVideoKeyframe coalesced (received=\(peerKeyframeRequests.received) admitted=\(peerKeyframeRequests.admitted))")
            }
            return
        }
        fileLog.log("📹 Received ForceVideoKeyframe notification — forcing IDR")
        forceKeyframe()
    }

    deinit {
        // Clean up synchronously without calling MainActor-isolated methods
        isStopped = true
        // 🔧 FIX: AVCaptureSession.stopRunning() is synchronous and can block the
        // caller for 100s of ms. deinit usually runs on the thread releasing the
        // last reference (often MAIN, when VideoCallView is dismissed at call end)
        // — running stopRunning() there froze the UI on hang-up. Hand the session
        // to a background queue; the local strong ref keeps it alive until stopped,
        // independent of `self` being deallocated. stopVideo() already nils the
        // session in the normal path, so this only fires on the unclean teardown.
        if let session = captureSession {
            // Same serial queue as every other start/stop, so this teardown cannot
            // overtake (or be overtaken by) a start that is still queued.
            sessionQueue.async { session.stopRunning() }
        }
        // 🔧 FIX: Clear static reference on deinit so next call starts clean
        VideoCallManager.lastActiveCaptureSession = nil
        captureSession = nil

        if let session = compressionSession {
            VTCompressionSessionInvalidate(session)
        }

        if let session = decompressionSession {
            VTDecompressionSessionInvalidate(session)
        }
        OshiLog.call.info("📹 VideoCallManager: DEINIT")
    }
    
    // MARK: - Session Key Management
    func setSessionKey(_ key: Data) {
        let wasNil = self.sessionKey == nil
        self.sessionKey = key
        let msg = "📹 VideoCallManager: Session key set (\(key.count) bytes)"
        OshiLog.call.info("\(msg)")
        fileLog.log(msg)
        // 🔧 FIX: If key was nil before, we dropped early packets including first keyframe
        // Force our own keyframe so remote can decode, and reset decoder for fresh IDR
        if wasNil {
            forceKeyframe()
            hasDecodedIDR = false  // Reset so decoder waits for fresh IDR before decoding P-frames
            fileLog.log("📹 VideoCallManager: Key was nil -> forcing keyframe + waiting for IDR")
        }
    }
    
    // nonisolated so it can be called from encode path
    nonisolated func getSessionKeyFromVoiceCall() -> Data? {
        // Try to get session key from VoiceCallManager
        // VoiceCallManager.sessionKey is a CryptoKit.SymmetricKey, need to convert to Data
        if let symmetricKey = voiceCallManager?.sessionKey {
            let keyData = symmetricKey.withUnsafeBytes { Data($0) }
            self.sessionKey = keyData
            OshiLog.call.info("📹 VideoCallManager: Got session key from VoiceCallManager (\(keyData.count) bytes)")
            return keyData
        }
        return sessionKey
    }
    
    // MARK: - Camera Control
    func startVideo() {
        let addr = Unmanaged.passUnretained(self).toOpaque()
        fileLog.log("📹 startVideo: BEGIN (\(addr)) isVideoEnabled=\(isVideoEnabled) captureSession=\(captureSession != nil)")

        // 📹 🔴 __LOCAL_CAPTURE_WATCHDOG_2026_08_22__ La garde était
        // `guard !isVideoEnabled`, et `isVideoEnabled` MENT dans les deux sens.
        //
        // Dans un sens: `startCapture()` le pose à `true` même après trois
        // tentatives où `session.isRunning` est resté faux (« proceeding
        // anyway »). Un démarrage raté laissait donc l'objet en « caméra
        // allumée », sans caméra — et cette garde INTERDISAIT alors toute
        // relance. C'est le défaut vécu par Natalia le 2026-08-21: elle ne se
        // voyait pas, et le seul remède était d'arrêter la vidéo puis de la
        // remettre, parce que l'arrêt est ce qui remet le drapeau à faux.
        //
        // Dans l'autre: il reste FAUX pendant toute la configuration
        // asynchrone, donc il ne protège pas non plus du double appel qu'il
        // prétend interdire. `isStartingVideo` fait ce travail-là.
        guard !isStartingVideo else {
            fileLog.log("⚠️ startVideo: démarrage déjà en cours — appel ignoré")
            return
        }
        if isVideoEnabled, let session = captureSession, session.isRunning {
            fileLog.log("⚠️ startVideo: caméra déjà en marche — appel ignoré")
            return
        }
        if isVideoEnabled {
            fileLog.log("⚠️ startVideo: isVideoEnabled=true mais la session ne tourne PAS — on relance au lieu de refuser")
        }
        isStartingVideo = true

        isStopped = false  // 🔧 Clear atomic stop flag
        isSendingEnabled = true  // 🔧 Clear atomic send flag
        // Re-open the RX gate that `stopVideo()` closes. Without this a
        // video→voice→video toggle inside one call would come back deaf: the
        // camera would send, and every frame from the peer would be dropped on
        // the first line of `receiveVideoPacket`.
        isReceivingEnabled = true
        receivingEnabledMirror = true
        isVideoFrozenForAudio = false  // 🛡️ start every call live; freeze only on measured stress
        lastFrozenKeyframeSentAt = .distantPast
        peerKeyframeRequests = PeerKeyframeRequests()  // fresh gate + warm-up grace per video session
        isUsingFrontCameraMirror = isUsingFrontCamera  // sync nonisolated mirror for encode callback
        Task {
            // La garde de ré-entrance se libère sur TOUS les chemins, y compris
            // les deux `return` de permission juste en dessous. Sans ce `defer`,
            // un refus de caméra bloquerait définitivement tout démarrage
            // ultérieur — l'utilisateur accorde l'accès et plus rien ne repart.
            defer { isStartingVideo = false }
            // 🔧 FIX: Check camera permission before starting capture
            let status = AVCaptureDevice.authorizationStatus(for: .video)
            fileLog.log("📹 startVideo: camera permission status=\(status.rawValue) (0=notDetermined, 1=restricted, 2=denied, 3=authorized)")
            if status == .notDetermined {
                let granted = await AVCaptureDevice.requestAccess(for: .video)
                guard granted else {
                    fileLog.log("❌ startVideo: Camera permission DENIED by user")
                    onError?("Camera permission denied")
                    return
                }
                fileLog.log("📹 startVideo: Camera permission GRANTED")
            } else if status != .authorized {
                fileLog.log("❌ startVideo: Camera NOT authorized (status: \(status.rawValue))")
                onError?("Camera not authorized")
                return
            }

            // 🔧 FIX (iPhone 17 PiP upside-down): start UIDevice orientation
            // notifications BEFORE setupCaptureSession so that the initial
            // `currentVideoRotationAngle()` call inside setupCaptureSession
            // can read a real physical orientation from UIDevice.current.orientation
            // (otherwise it returns .unknown and falls back to a possibly
            // wrong interfaceOrientation, locking the capture rotation to 270°
            // and producing an upside-down portrait buffer for the call).
            #if os(iOS)
            UIDevice.current.beginGeneratingDeviceOrientationNotifications()
            #endif
            fileLog.log("📹 startVideo: calling setupCaptureSession...")
            await setupCaptureSession()
            fileLog.log("📹 startVideo: calling startCapture...")
            await startCapture()
            startOrientationObserver()
            // 📡 2026-06-08: adaptive bitrate — watch network class and throttle
            // encoder when the path degrades (Wi-Fi→5G handoff, low-data mode).
            startAdaptiveBitrateMonitor()
            startRemoteHealthMonitor()
            armLocalCaptureWatchdog()
            fileLog.log("📹 startVideo: DONE — isVideoEnabled=\(isVideoEnabled) running=\(captureSession?.isRunning ?? false)")
        }
    }
    
    // MARK: - 📹 Le démarrage caméra peut échouer EN SILENCE
    //
    // __LOCAL_CAPTURE_WATCHDOG_2026_08_22__ Appel du 2026-08-21 avec Natalia:
    // « la vidéo n'a pas démarré chez elle, elle ne se voyait pas, elle a dû
    // arrêter la vidéo et la remettre pour se voir. »
    //
    // Ce que faisait le code: `startCapture()` lance la session sur
    // `sessionQueue`, attend 200 ms puis 500 ms puis 500 ms, et si
    // `session.isRunning` est resté faux, pose quand même `isVideoEnabled = true`
    // en notant « proceeding anyway ». Personne ne repasse jamais. La session ne
    // démarre plus, aucune image n'arrive, `localVideoImage` reste nil — donc pas
    // d'aperçu de soi — et le pair ne reçoit rien non plus. Pire: `startVideo()`
    // refusait de relancer, parce que sa garde lisait ce même `isVideoEnabled`.
    // Seul un arrêt complet remettait le drapeau à faux: exactement le geste que
    // Natalia a dû trouver toute seule.
    //
    // Un démarrage d'AVCaptureSession peut échouer pour des raisons parfaitement
    // ordinaires et NON définitives: l'app est encore en arrière-plan quand
    // CallKit décroche, la caméra est tenue une fraction de seconde par la
    // session de l'appel précédent, une autre app la relâche en retard, le
    // système interrompt la capture (multitâche, appel entrant).
    //
    // D'où cette surveillance. Elle ne regarde ni un drapeau ni `isRunning` —
    // les deux peuvent être vrais sans qu'une seule image sorte — mais le
    // compteur d'images RÉELLEMENT capturées. Trois relances espacées, puis on
    // laisse tomber: au-delà, ce n'est plus un démarrage lent, c'est une caméra
    // qu'on n'aura pas.
    private func armLocalCaptureWatchdog() {
        localCaptureWatchdog?.cancel()
        let baseline = capturedFrameCount
        localCaptureWatchdog = Task { @MainActor [weak self] in
            var restarts = 0
            for attempt in 1...3 {
                try? await Task.sleep(nanoseconds: 3_500_000_000)
                guard let self, !Task.isCancelled else { return }

                // Caméra volontairement éteinte entre-temps (arrêt, « watch
                // only », bascule vidéo→voix): il n'y a rien à réparer.
                guard !self.isStopped, self.isSendingVideo else {
                    self.fileLog.log("📹 watchdog: caméra volontairement éteinte — surveillance terminée")
                    return
                }

                if self.capturedFrameCount > baseline {
                    self.fileLog.log("📹 watchdog: \(self.capturedFrameCount - baseline) image(s) capturée(s) — caméra vivante")
                    return
                }

                #if os(iOS)
                // En arrière-plan, iOS REFUSE de démarrer la capture pour une app
                // tierce. Relancer là ne ferait qu'échouer une fois de plus et
                // gaspiller une des trois tentatives; on attend le retour au
                // premier plan (`resumeCapture()` s'en charge aussi de son côté).
                if UIApplication.shared.applicationState == .background {
                    self.fileLog.log("📹 watchdog: app en arrière-plan — pas de relance, on attend le premier plan")
                    continue
                }
                #endif

                restarts += 1
                self.fileLog.log("⚠️ watchdog: AUCUNE image capturée après \(attempt) fenêtre(s) — relance \(restarts)/3")
                await self.restartCaptureAfterSilentFailure()
            }
            // On distingue les deux fins: trois relances vraiment tentées, ou
            // trois fenêtres passées en arrière-plan sans jamais pouvoir essayer.
            // Dans le second cas ce n'est pas un échec — `resumeCapture()` et la
            // fin d'interruption réarment la surveillance au retour à l'écran.
            if restarts > 0 {
                self?.fileLog.log("❌ watchdog: \(restarts) relance(s) sans une seule image — la caméra ne démarrera pas")
            } else {
                self?.fileLog.log("📹 watchdog: fenêtres écoulées en arrière-plan — surveillance rendue, réarmement au premier plan")
            }
        }
    }

    private func cancelLocalCaptureWatchdog() {
        localCaptureWatchdog?.cancel()
        localCaptureWatchdog = nil
    }

    /// Reconstruit la session de capture — le geste que l'utilisatrice a dû
    /// faire à la main. `setupCaptureSession()` démonte déjà la session
    /// existante, donc l'appeler suffit à repartir d'une caméra propre.
    private func restartCaptureAfterSilentFailure() async {
        guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else {
            fileLog.log("📹 watchdog: caméra non autorisée — rien à relancer")
            return
        }
        isStopped = false
        isSendingEnabled = true
        await setupCaptureSession()
        await startCapture()
        forceKeyframe()
        fileLog.log("📹 watchdog: relance faite — running=\(captureSession?.isRunning ?? false)")
    }

    /// Receive-only entry: the user accepted a video upgrade as "watch only".
    /// We render the peer's video but do NOT start our camera. The user can
    /// later turn their camera on via setLocalCameraEnabled(true).
    func startVideoReceiveOnly() {
        let addr = Unmanaged.passUnretained(self).toOpaque()
        fileLog.log("📹 startVideoReceiveOnly: BEGIN (\(addr)) — receive-only, camera OFF")
        isStopped = false
        isSendingVideo = false
        isSendingEnabled = false
        isReceivingEnabled = true
        receivingEnabledMirror = true
        startRemoteHealthMonitor()
        // No capture session / encoder. Remote frames render via displayLayer as
        // they arrive (isReceivingVideo flips true in the decode path).
    }

    /// Turn THIS device's camera on/off mid-video without leaving the video call.
    /// on=true:  first time (was receive-only) → full startVideo(); otherwise
    ///           resume the paused capture session.
    /// on=false: stop the capture session (camera light off) but KEEP receiving
    ///           and rendering the peer's video.
    func setLocalCameraEnabled(_ on: Bool) {
        fileLog.log("📹 setLocalCameraEnabled(\(on)) — captureSession=\(captureSession != nil) isVideoEnabled=\(isVideoEnabled)")
        if on {
            if captureSession == nil {
                startVideo()
            } else {
                resumeCapture()
                isSendingVideo = true
                isSendingEnabled = true
            }
        } else {
            cancelLocalCaptureWatchdog()  // extinction VOULUE de la caméra
            pauseCapture()
            isSendingVideo = false
            localVideoImage = nil
        }
    }

    func stopVideo() {
        let addr = Unmanaged.passUnretained(self).toOpaque()
        fileLog.log("📹 stopVideo: BEGIN (\(addr)) isVideoEnabled=\(isVideoEnabled)")

        // 🔧 FIX: Guard against double-stop (prevents redundant cleanup)
        guard !isStopped else {
            fileLog.log("⚠️ stopVideo: already stopped — ignoring")
            return
        }

        isStopped = true  // 🔧 Atomic flag: stop capture callback immediately
        isSendingEnabled = false  // 🔧 Atomic flag: stop encode path immediately
        cancelLocalCaptureWatchdog()  // arrêt VOULU: plus rien à surveiller
        // 🔴 FIX (2026-08-20): the RECEIVE path had no equivalent. The encode
        // side got `isSendingEnabled` on the very first line of teardown; the
        // decode side kept accepting the peer's in-flight frames all the way
        // down to `VTDecompressionSessionDecodeFrame` while this function
        // dismantled the decoder around it. Close the gate first — the lock
        // below is what makes it SAFE, this is what makes it SHORT.
        receivingEnabledMirror = false
        isVideoEnabled = false
        isSendingVideo = false
        teardownPiP()
        // Defer displayLayer.flush() until AFTER the SwiftUI dismissal animation
        // completes. Calling flush() synchronously while VideoCallView is being
        // removed from the hierarchy froze the iPhone whenever the Android peer
        // hung up — AVSampleBufferDisplayLayer's flush blocks on pending sample
        // enqueues, and CoreAnimation was holding the main thread for the
        // dismissal transition. Async + small delay lets the view detach first.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
            self?.displayLayer.flush()
        }
        stopOrientationObserver()
        stopAdaptiveBitrateMonitor()
        stopRemoteHealthMonitor()
        videoTxCount = 0
        videoRxCount = 0
        decodedFrameCount = 0
        hasDecodedIDR = false
        resetDecodeFailures()
        successfulDecodes = 0
        lastDecoderCreationRx = 0
        encodingStartTime = nil

        // 📹 Reset cross-platform fragmentation state (iOS↔Android) so a new call
        // doesn't reassemble against stale entries / a stale rxLastCompletedFrameId.
        os_unfair_lock_lock(&reassemblyLock)
        reassemblyMap.removeAll()
        reassemblyOrder.removeAll()
        rxLastCompletedFrameId = -1
        txFrameId = 0
        diagFragTxCount = 0
        diagFragRxCount = 0
        rxCompletedFrames = 0
        rxLostFrames = 0
        os_unfair_lock_unlock(&reassemblyLock)
        diagLastKeyframeBytes = 0
        diagMaxKeyframeBytes = 0
        diagKeyframeRequestsSent = 0
        diagLastKeyframeRequestAt = .distantPast
        diagOversizeFramesDropped = 0

        // 🔐 CRYPTO: reset the AES-GCM counter AND force a fresh transmit salt.
        //
        // 🔧 FIX (nonce reuse): `_videoNonce` was never reset anywhere, and the salt
        // did not exist. Resetting the counter ALONE would be actively harmful — a
        // voice→video→voice→video toggle inside ONE call reuses the same session key,
        // so counter 1 would be emitted twice under one key. Clearing `_videoTxSalt`
        // here makes the next video session derive fresh salt entropy (see
        // `videoTxSaltLocked`), so the (salt, counter) pair is unique per session.
        // Held under `nonceLock` — NOT `reassemblyLock`; they guard disjoint state.
        os_unfair_lock_lock(&nonceLock)
        _videoNonce = 0
        _videoTxSalt = nil
        os_unfair_lock_unlock(&nonceLock)

        // 🔐 Drop the RX anti-replay window: nonces from the finished session must not
        // shadow the next one (and the window would otherwise leak across calls).
        os_unfair_lock_lock(&replayLock)
        seenNonces.removeAll()
        seenNonceOrder.removeAll()
        replayDropCount = 0
        os_unfair_lock_unlock(&replayLock)

        // 🔧 FIX: Remove delegate FIRST to stop new frames from being delivered
        videoOutput?.setSampleBufferDelegate(nil, queue: nil)

        // 🔧 FIX: Stop capture session ASYNCHRONOUSLY to prevent deadlock. Previously
        // used videoQueue.sync, which deadlocked the main thread when a capture callback
        // was in-flight (callback dispatches to main.async → main blocked on sync →
        // deadlock). Now on `sessionQueue`: stopRunning() waits for in-flight delegate
        // callbacks, so it must NOT be issued from `videoQueue`, which is the delegate
        // queue itself. Camera resources are released when the session stops +
        // references are nilled below.
        let session = captureSession
        sessionQueue.async {
            session?.stopRunning()
        }

        // 🔧 FIX: Clear static reference so next call doesn't try to stop an already-stopped session
        VideoCallManager.lastActiveCaptureSession = nil

        // Nil out session references to break retain cycles and release camera
        captureSession = nil
        videoOutput = nil
        currentCameraInput = nil

        // 🔧 FIX: Invalidate VT sessions on background queue to prevent main thread freeze
        // VTCompressionSessionInvalidate can block if pending frames are in-flight
        //
        // 🔴 FIX (2026-08-20): and take `decoderLock` before touching the DECODE
        // session. Nilling it here does not stop a decode that is already past
        // its `guard` — the peer's in-flight frames keep arriving for hundreds of
        // milliseconds after `0x0F`, and `receiveVideoPacket` has no stop gate at
        // all. Invalidating underneath such a decode is the crash that ended the
        // 2026-08-20 call. Full reasoning on `decoderLock`.
        let compSession = compressionSession
        compressionSession = nil
        os_unfair_lock_lock(&decoderLock)
        let decompSession = decompressionSession
        decompressionSession = nil
        os_unfair_lock_unlock(&decoderLock)
        // VTCompressionSession/VTDecompressionSession are C types — safe to use across threads
        nonisolated(unsafe) let comp = compSession
        nonisolated(unsafe) let decomp = decompSession
        DispatchQueue.global(qos: .utility).async { [weak self] in
            if let comp { VTCompressionSessionInvalidate(comp) }
            guard let decomp else { return }
            // Re-take the lock: a decode that started BEFORE the nil above is
            // still holding the handle. This waits it out instead of pulling the
            // decoder out from under it, and it runs here — off the main thread —
            // precisely because waiting can block.
            if let self { os_unfair_lock_lock(&self.decoderLock) }
            VTDecompressionSessionWaitForAsynchronousFrames(decomp)
            VTDecompressionSessionInvalidate(decomp)
            if let self { os_unfair_lock_unlock(&self.decoderLock) }
        }
        decoderFormatDescription = nil

        // 🔧 FIX: Clear callbacks to prevent stale references
        onVideoPacketReady = nil

        // 🔧 FIX: Clear cached SPS/PPS to prevent stale decoder params on next call
        cachedSPS = nil
        cachedPPS = nil
        receivedCachedSPS = nil
        receivedCachedPPS = nil

        localVideoImage = nil
        isReceivingVideo = false

        fileLog.log("📹 stopVideo: DONE (\(addr))")
    }
    
    func switchCamera() {
        guard let session = captureSession else {
            fileLog.log("⚠️ switchCamera: no captureSession — ignoring")
            return
        }

        fileLog.log("📹 switchCamera: BEGIN (current=\(isUsingFrontCamera ? "front" : "back"))")

        // The @Published flip stays on the main actor (the self-view mirrors on it);
        // only the AVFoundation reconfiguration moves off.
        isUsingFrontCamera.toggle()
        isUsingFrontCameraMirror = isUsingFrontCamera  // sync nonisolated mirror for encode callback

        let wantFront = isUsingFrontCamera
        let oldInput = currentCameraInput
        let output = videoOutput
        let angle = currentRotationAngle

        // 🔧 FIX (main-thread stall): removeInput/addInput inside
        // begin/commitConfiguration is the same blocking hardware round-trip as
        // first setup — it belongs on `sessionQueue`, not the runloop.
        sessionQueue.async { [weak self] in
            guard let self else { return }
            session.beginConfiguration()

            if let oldInput { session.removeInput(oldInput) }

            var newlyAdded: AVCaptureDeviceInput?
            if let newCamera = self.getCamera(front: wantFront),
               let newInput = try? AVCaptureDeviceInput(device: newCamera) {
                if session.canAddInput(newInput) {
                    session.addInput(newInput)
                    newlyAdded = newInput
                } else {
                    self.fileLog.log("❌ switchCamera: canAddInput returned false")
                }
            } else {
                self.fileLog.log("❌ switchCamera: failed to get \(wantFront ? "front" : "back") camera")
            }

            // 📐 Update mirroring for new camera + maintain current rotation angle
            if let output, let connection = output.connection(with: .video) {
                if connection.isVideoMirroringSupported {
                    connection.isVideoMirrored = wantFront
                }
                if connection.isVideoRotationAngleSupported(angle) {
                    connection.videoRotationAngle = angle
                }
            }

            session.commitConfiguration()

            // Republish the owning reference + force IDR back on the main actor.
            nonisolated(unsafe) let addedInput = newlyAdded
            Task { @MainActor [weak self] in
                guard let self else { return }
                if let addedInput { self.currentCameraInput = addedInput }
                // 🔧 FIX: Force IDR keyframe after camera switch so remote gets a clean frame
                self.forceKeyframe()
                self.fileLog.log("📹 switchCamera: DONE → \(wantFront ? "front" : "back") camera + forced IDR")
            }
        }
    }

    // MARK: - Pause/Resume Local Video (lightweight toggle)
    // Unlike stopVideo()/startVideo() which do full teardown/setup,
    // these methods just pause/resume the camera without destroying callbacks or session.

    /// Pause local video — stops camera capture but keeps session, encoder, and callbacks alive.
    /// Use this for the in-call camera toggle button.
    func pauseLocalVideo() {
        guard isVideoEnabled else {
            fileLog.log("⚠️ pauseLocalVideo: already paused — ignoring")
            return
        }
        fileLog.log("📹 pauseLocalVideo: stopping camera (keeping session/callbacks alive)")
        cancelLocalCaptureWatchdog()  // pause VOULUE: la surveillance n'a rien à réparer
        isSendingEnabled = false
        isSendingVideo = false
        isVideoEnabled = false
        localVideoImage = nil

        // Stop capture session to save battery (but keep the session object).
        // On `sessionQueue` — see the sessionQueue doc comment for why not videoQueue.
        if let session = captureSession, session.isRunning {
            let ref = session
            sessionQueue.async { ref.stopRunning() }
        }
    }

    /// Resume local video — restarts camera capture and encoder from paused state.
    /// Falls back to full startVideo() if session was destroyed.
    func resumeLocalVideo() {
        guard !isVideoEnabled else {
            fileLog.log("⚠️ resumeLocalVideo: already running — ignoring")
            return
        }

        // If session was destroyed (e.g., by full stopVideo), fall back to full start
        guard captureSession != nil else {
            fileLog.log("📹 resumeLocalVideo: no session — falling back to full startVideo()")
            startVideo()
            return
        }

        fileLog.log("📹 resumeLocalVideo: restarting camera from paused state")
        isStopped = false
        isSendingEnabled = true
        isSendingVideo = true
        isVideoEnabled = true

        // Restart capture session (serialised behind any pending stop on sessionQueue)
        if let session = captureSession, !session.isRunning {
            let ref = session
            sessionQueue.async { ref.startRunning() }
        }

        // Recreate encoder if it was invalidated, then force IDR
        Task {
            if compressionSession == nil {
                fileLog.log("📹 resumeLocalVideo: encoder was nil — recreating")
                await setupEncoder()
            }
            forceKeyframe()
            fileLog.log("📹 resumeLocalVideo: done, sending=\(isSendingEnabled) running=\(captureSession?.isRunning ?? false)")
        }
        // Une reprise est un démarrage: elle peut échouer aussi silencieusement
        // qu'un premier lancement. Voir armLocalCaptureWatchdog.
        armLocalCaptureWatchdog()
    }

    // 🔧 NEW: Toggle sending video
    func toggleSendingVideo() {
        isSendingVideo.toggle()
        isSendingEnabled = isSendingVideo  // Sync nonisolated flag
        OshiLog.call.info("📹 VideoCallManager: Sending video \(isSendingVideo ? "enabled" : "disabled")")
    }

    // 🔧 NEW: Toggle receiving video
    func toggleReceivingVideo() {
        isReceivingEnabled.toggle()
        receivingEnabledMirror = isReceivingEnabled  // Sync nonisolated mirror
        if !isReceivingEnabled {
                isReceivingVideo = false
        }
        OshiLog.call.info("📹 VideoCallManager: Receiving video \(isReceivingEnabled ? "enabled" : "disabled")")
    }


    /// Force the encoder to produce an IDR keyframe on the next frame
    /// Call this when the call connects so the other side can start decoding immediately
    func forceKeyframe() {
        lastKeyFrameTime = Date.distantPast  // Next encodeFrame will force IDR
        fileLog.log("📹 forceKeyframe: will produce IDR on next encode")
    }

    func setQuality(_ quality: VideoQuality) {
        currentQuality = quality
        updateEncoderSettings()
        OshiLog.call.info("📹 VideoCallManager: Quality set to \(quality.rawValue)")
    }

    // MARK: - Adaptive Bitrate (2026-06-08, Bug B fix)
    //
    // Live-patch the VTCompressionSession bitrate WITHOUT recreating the
    // encoder (resolution change is destructive — receiver must re-decode
    // a fresh SPS/PPS chain and several macroblock rows turn ugly during
    // the transition). Just changing AverageBitRate + DataRateLimits lets
    // the encoder smoothly throttle within the same resolution.
    private func applyAdaptiveBitrate(kbps: Int) {
        guard let session = compressionSession else { return }
        let bps = max(150_000, kbps * 1000)
        // Clamp at the resolution's preset ceiling — going above just wastes
        // bits on a fixed-size frame.
        let ceiling = currentQuality.bitrate
        let target = min(bps, ceiling)
        if abs(target - lastAppliedBitrate) < 50_000 { return }  // ignore <50 kbps jitter
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate, value: target as CFNumber)
        // 2.5x to match the encoder-creation burst headroom (was 1.8x) — otherwise
        // the first 2 Hz adapter tick clawed the motion burst back to 1.8x on a good
        // link and the pixelation returned. The burst is a multiple of `target`,
        // which the adapter has already lowered on a constrained network, so this
        // stays proportional and safe.
        let peakBytes = NSNumber(value: Double(target) * 2.5 / 8.0)
        let dataRateLimits = [peakBytes, NSNumber(value: 1.0)] as CFArray
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_DataRateLimits, value: dataRateLimits)
        lastAppliedBitrate = target
        Task { @MainActor in self.currentBitrateKbps = target / 1000 }
        fileLog.log("📹 ABR: bitrate → \(target/1000) kbps (ceiling \(ceiling/1000), net=\(currentNetworkClass))")
    }

    /// Start watching network conditions + periodic adapter. Idempotent.
    /// Called from startVideo so the monitor lifetime tracks the call.
    private func startAdaptiveBitrateMonitor() {
        guard pathMonitor == nil else { return }
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let usesCell = path.usesInterfaceType(.cellular)
            let usesWifi = path.usesInterfaceType(.wifi)
            self.pathUsesCellular = usesCell
            self.pathUsesWifi = usesWifi
            self.isOnExpensivePath = path.isExpensive
            self.isOnConstrainedPath = path.isConstrained
            let klass: String
            if path.status != .satisfied {
                klass = "down"
            } else if path.isConstrained {
                klass = "constrained"
            } else if usesWifi {
                klass = "wifi"
            } else if usesCell {
                klass = path.isExpensive ? "cellular" : "cellular-cheap"
            } else {
                klass = path.isExpensive ? "expensive" : "other"
            }
            Task { @MainActor in self.currentNetworkClass = klass }
            fileLog.log("📡 ABR: path update class=\(klass) wifi=\(usesWifi) cell=\(usesCell) exp=\(path.isExpensive) cons=\(path.isConstrained)")
        }
        monitor.start(queue: pathMonitorQueue)
        pathMonitor = monitor

        // 2 Hz adapter — re-evaluates target bitrate from latest path state.
        // Higher cadence isn't useful (NWPath only fires on transitions).
        bitrateAdapterTimer?.invalidate()
        let timer = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in
            // Timer fires on the runloop thread (.common mode added to main RL
            // below); hop to MainActor explicitly because tickAdaptiveBitrate
            // touches the encoder + reads @MainActor state.
            Task { @MainActor [weak self] in self?.tickAdaptiveBitrate() }
        }
        RunLoop.main.add(timer, forMode: .common)
        bitrateAdapterTimer = timer
        fileLog.log("📡 ABR: monitor started")
    }

    private func stopAdaptiveBitrateMonitor() {
        pathMonitor?.cancel()
        pathMonitor = nil
        bitrateAdapterTimer?.invalidate()
        bitrateAdapterTimer = nil
        lastAppliedBitrate = 0
        adaptiveBitrateMultiplier = 1.0
        fileLog.log("📡 ABR: monitor stopped")
    }

    /// Recompute target bitrate from current path class.
    ///
    /// Ladder (multiplier on `currentQuality.bitrate`):
    ///   • wifi or unconstrained ethernet  → 1.0   (full)
    ///   • cellular (any) unconstrained    → 0.60  (Wi-Fi→5G fall-off)
    ///   • cellular expensive (paid roaming, hotspot tether) → 0.35
    ///   • constrained (iOS Low-Data mode) → 0.25
    ///   • path down                       → 0.20  (keep encoder alive at floor)
    ///
    /// The multiplier is EMA-smoothed (α=0.4) so a momentary flap doesn't
    /// produce a visible quality jolt.
    private func tickAdaptiveBitrate() {
        guard compressionSession != nil, isSendingEnabled else { return }
        var targetMultiplier: Double
        if isOnConstrainedPath {
            targetMultiplier = 0.25
        } else if pathUsesWifi {
            targetMultiplier = 1.0
        } else if pathUsesCellular {
            targetMultiplier = isOnExpensivePath ? 0.35 : 0.60
        } else if pathMonitor?.currentPath.status != .satisfied {
            targetMultiplier = 0.20
        } else {
            targetMultiplier = 0.80  // unknown but live (e.g., wired ethernet)
        }

        // 🛡️ AUDIO-PRIORITY: fold in the REAL measured-loss/RTT congestion the
        // audio loop sees. The NWPath class above only knows the interface
        // (wifi/cellular) — it can't tell a clean Wi-Fi from a saturated one
        // (China evening: nominal Wi-Fi, heavy loss). The audio controller does.
        // Multiplying by `audioCongestionFactor` (1.0 pristine → 0.0 saturated)
        // makes VIDEO give up bits FIRST, before audio's protected floor is ever
        // touched. Worst case the two multipliers compound (e.g. cellular 0.60 ×
        // congestion 0.3 = 0.18), which is exactly what we want on a thin link.
        let congestion = voiceCallManager?.audioCongestionFactor ?? 1.0
        // __VIDEO_ABR_2026_09_23__ …and the peer's keyframe requests: the one signal about
        // OUR uplink (audio loss is measured on what WE receive). Android and the desktop
        // now adapt on the same signals with the same constants (VideoRateController.kt),
        // so each client reacts to the other's PLIs.
        let pliPressure = peerKeyframeRequests.pressureFactor()
        targetMultiplier *= min(congestion, pliPressure)

        // Smooth — converge over ~1.5s at 0.5s cadence.
        adaptiveBitrateMultiplier = 0.6 * adaptiveBitrateMultiplier + 0.4 * targetMultiplier

        // 🛡️ FREEZE / THAW with hysteresis so we don't flap on a single bad probe:
        //   • freeze when audio is actively stressed (factor < 0.35) — video stops
        //     sending P-frames, hands the pipe to voice (keyframe-only trickle).
        //   • thaw only once headroom is comfortably back (factor > 0.55), so a
        //     borderline link doesn't oscillate frozen↔live every half second.
        // This is in ADDITION to the bitrate throttle above: bitrate reduction is
        // the first line of defence, freeze is the last before audio would suffer.
        let stressed = voiceCallManager?.isAudioPathStressed ?? false
        if isVideoFrozenForAudio {
            // 🔧 2026-06-29 (2-min video-freeze fix): THAW as soon as audio is no
            // longer stressed — the SAME signal that froze us. The old condition
            // (`congestion > 0.55`) used a DIFFERENT signal: on a constrained /
            // cellular ("expensive") path the smoothed factor is capped around
            // 0.35–0.60 and frequently never climbs back above 0.55, so after a
            // brief audio blip froze the video it stayed frozen for the REST of the
            // call even though audio had fully recovered — the "video freezes ~2 min
            // in, audio stays perfect" report. Freeze is gated on `stressed`; thaw
            // must be gated on `!stressed`. Bitrate adaptation (above) still handles
            // genuine bandwidth limits independently.
            if !stressed {
                isVideoFrozenForAudio = false
                lastKeyFrameTime = .distantPast  // force a fresh IDR the instant we thaw
                fileLog.log("🛡️ Video THAW: audio path no longer stressed (factor=\(String(format: "%.2f", congestion))) — resuming full frame rate")
            }
        } else if stressed {
            isVideoFrozenForAudio = true
            lastFrozenKeyframeSentAt = .distantPast  // emit a keyframe immediately on freeze
            fileLog.log("🛡️ Video FREEZE: audio path stressed (factor=\(String(format: "%.2f", congestion))) — dropping to keyframe-only to protect voice")
        }

        let targetKbps = Int(Double(currentQuality.bitrate) * adaptiveBitrateMultiplier) / 1000
        applyAdaptiveBitrate(kbps: targetKbps)
    }
    
    // MARK: - Remote video health

    /// Measure what is ACTUALLY arriving, once a second.
    ///
    /// Runs for both `startVideo` and `startVideoReceiveOnly` — a watch-only
    /// participant is precisely the person who needs to be told that nothing is
    /// coming, since they have no self-preview to prove the call is alive.
    private func startRemoteHealthMonitor() {
        remoteHealthTimer?.invalidate()
        lastHealthFrameCount = decodedFrameCount
        lastRemoteFrameAt = nil
        remoteVideoFPS = 0
        isRemoteVideoStalled = false
        // __PEER_VIDEO_NEVER_ARRIVED_2026_08_26__ Le chronomètre de l'escalade.
        remoteHealthStartedAt = Date()
        neverArrivedTicks = 0
        isRemoteVideoNeverArrived = false
        let timer = Timer(timeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tickRemoteHealth() }
        }
        RunLoop.main.add(timer, forMode: .common)
        remoteHealthTimer = timer
    }

    private func stopRemoteHealthMonitor() {
        remoteHealthTimer?.invalidate()
        remoteHealthTimer = nil
        remoteVideoFPS = 0
        isRemoteVideoStalled = false
        lastRemoteFrameAt = nil
        remoteHealthStartedAt = nil
        neverArrivedTicks = 0
        isRemoteVideoNeverArrived = false
    }

    private func tickRemoteHealth() {
        let count = decodedFrameCount
        let delta = max(0, count - lastHealthFrameCount)
        lastHealthFrameCount = count
        let fps = Double(delta)
        if delta > 0 { lastRemoteFrameAt = Date() }

        // Nothing has EVER arrived: that is "connecting", not "stalled" — the
        // placeholder in the view already says so, and contradicting it with a
        // warning badge would be noise on every single call.
        //
        // __PEER_VIDEO_NEVER_ARRIVED_2026_08_26__ True, and it was also the end
        // of the story, which is the defect. "Connecting" had no deadline: a
        // call where not one frame ever decoded sat under that word for its
        // whole duration, emitted no log line, and — the part that actually cost
        // the picture — never asked for another keyframe. The three startup IDR
        // requests are all fired within 2.3 s of the view appearing; a peer
        // whose encoder came up later than that could not be recovered by
        // anything, because every other `requestKeyframeFromPeer()` call site
        // lives in the RECEIVE path, and the receive path never ran.
        //
        // So "connecting" keeps its grace period, and then it has to answer for
        // itself. See `isRemoteVideoNeverArrived`.
        let stalled: Bool
        if let last = lastRemoteFrameAt {
            stalled = Date().timeIntervalSince(last) >= remoteStallSeconds
        } else {
            stalled = false
        }

        if lastRemoteFrameAt == nil, let startedAt = remoteHealthStartedAt {
            let waited = Date().timeIntervalSince(startedAt)
            if waited >= remoteNeverArrivedSeconds {
                if !isRemoteVideoNeverArrived {
                    isRemoteVideoNeverArrived = true
                    let m = "DIAG_VIDEO_RX_NEVER | waitedS=\(String(format: "%.1f", waited)) | decoded=\(count) | rxPackets=\(videoRxCount) | receivingEnabled=\(receivingEnabledMirror)"
                    OshiLog.call.info("\(m)"); fileLog.log(m)
                }
                // Keep asking. `sendKeyframeRequest` has its own token bucket, so
                // this cannot become a PLI storm; the tick divisor is here so the
                // cadence stays legible in the log rather than being an emergent
                // property of two rate limiters.
                //
                // `videoRxCount` in the line above is the load-bearing half of the
                // diagnosis and the reason this is worth logging at all: packets
                // arriving but nothing decoding is a DECODER problem on this side,
                // while zero packets is the peer not sending. Those two have
                // opposite fixes and looked identical from the screen.
                if neverArrivedTicks % neverArrivedKeyframeEveryTicks == 0 {
                    requestKeyframeFromPeer()
                }
                neverArrivedTicks += 1
            }
        } else if isRemoteVideoNeverArrived {
            // A frame finally decoded. Say so — a recovery that leaves no trace
            // is indistinguishable from a defect that was never real.
            isRemoteVideoNeverArrived = false
            neverArrivedTicks = 0
            let m = "DIAG_VIDEO_RX_RECOVERED | decoded=\(count) | rxPackets=\(videoRxCount)"
            OshiLog.call.info("\(m)"); fileLog.log(m)
        }

        if abs(fps - remoteVideoFPS) >= 0.5 || (fps == 0) != (remoteVideoFPS == 0) {
            remoteVideoFPS = fps
        }
        if stalled != isRemoteVideoStalled {
            isRemoteVideoStalled = stalled
            fileLog.log("DIAG_VIDEO_RX_HEALTH | stalled=\(stalled) | fps=\(fps) | decoded=\(count)")
        }
    }

    /// True while frames arrive but far too slowly to read as video. Distinct
    /// from stalled: something IS coming, it just is not a moving picture.
    var isRemoteVideoWeak: Bool {
        isReceivingVideo && !isRemoteVideoStalled && remoteVideoFPS > 0 && remoteVideoFPS < remoteWeakFPS
    }

    func setBlurMode(_ mode: VideoBlurMode) {
        blurMode = mode
        currentBlurMode = mode  // Sync nonisolated flag
        currentBlurIntensity = blurIntensity
        OshiLog.call.info("📹 VideoCallManager: Blur mode set to \(mode.rawValue)")
    }

    func setBlurIntensity(_ intensity: Double) {
        blurIntensity = intensity
        currentBlurIntensity = intensity
    }

    // MARK: - Orientation Handling

    /// Start observing device orientation changes to adapt video capture + encoder
    private func startOrientationObserver() {
        #if os(iOS)
        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        // 🔧 FIX (0x8BADF00D watchdog shape, same as 6ec6ca9/16be239): this used
        // `queue: .main`, which makes NotificationCenter run the block as an
        // NSOperation and BLOCK the posting thread on `-[NSOperation waitUntilFinished]`
        // until main drains. UIDevice orientation notifications are posted from
        // UIKit's own machinery, and any background queue that ends up posting into
        // this centre while main is waiting on that queue completes the deadlock
        // cycle. `queue: nil` runs the block inline on the poster; the handler
        // already hops to the main actor itself, so behaviour is unchanged.
        orientationObserver = NotificationCenter.default.addObserver(
            forName: UIDevice.orientationDidChangeNotification,
            object: nil, queue: nil
        ) { [weak self] _ in
            guard let self = self else { return }
            Task { @MainActor in
                self.handleOrientationChange()
            }
        }
        fileLog.log("📹 Orientation observer started")
        #endif
    }

    /// Stop observing orientation changes
    private func stopOrientationObserver() {
        #if os(iOS)
        if let observer = orientationObserver {
            NotificationCenter.default.removeObserver(observer)
            orientationObserver = nil
        }
        UIDevice.current.endGeneratingDeviceOrientationNotifications()
        fileLog.log("📹 Orientation observer stopped")
        #endif
    }

    /// Handle device orientation change: update capture connection + recreate encoder
    private func handleOrientationChange() {
        let newAngle = currentVideoRotationAngle()

        // Skip if orientation hasn't actually changed (e.g., faceUp/faceDown)
        guard newAngle != currentRotationAngle else { return }

        let oldAngle = currentRotationAngle
        currentRotationAngle = newAngle
        fileLog.log("📹 Orientation changed: \(oldAngle)° → \(newAngle)°")

        // Update capture connection rotation angle (iOS 17+ API)
        if let output = videoOutput, let connection = output.connection(with: .video) {
            if connection.isVideoRotationAngleSupported(newAngle) {
                connection.videoRotationAngle = newAngle
            }
            // Maintain mirroring for front camera
            if connection.isVideoMirroringSupported {
                connection.isVideoMirrored = isUsingFrontCamera
            }
        }

        // Recreate encoder with matching dimensions (portrait vs landscape)
        // VTCompressionSession dimensions are fixed at creation time — must recreate
        if let oldSession = compressionSession {
            VTCompressionSessionInvalidate(oldSession)
            compressionSession = nil
        }
        Task {
            await setupEncoder()
            // Force keyframe so receiver can decode immediately after dimension change
            lastKeyFrameTime = Date.distantPast
            fileLog.log("📹 Encoder recreated for new orientation, keyframe forced")
        }
    }

    // MARK: - Private Setup

    /// Transport box for handing configured AVFoundation objects back from
    /// `sessionQueue` to the main actor. AVCaptureSession/Input/Output are not
    /// Sendable; the box is `@unchecked Sendable` because exactly one queue touches
    /// the objects at a time (sessionQueue builds them, then hands ownership over).
    private final class CaptureSetupResult: @unchecked Sendable {
        let session: AVCaptureSession?
        let output: AVCaptureVideoDataOutput?
        let input: AVCaptureDeviceInput?
        let errorMessage: String?
        init(session: AVCaptureSession?, output: AVCaptureVideoDataOutput?, input: AVCaptureDeviceInput?, errorMessage: String? = nil) {
            self.session = session
            self.output = output
            self.input = input
            self.errorMessage = errorMessage
        }
    }

    private func setupCaptureSession() async {
        fileLog.log("📹 setupCaptureSession: BEGIN (existing session: \(captureSession != nil))")

        // 🔧 FIX: Clean up any existing session before creating a new one.
        // Detaching the delegate is cheap and must happen before the stop is queued.
        if let oldSession = captureSession {
            videoOutput?.setSampleBufferDelegate(nil, queue: nil)
            sessionQueue.async { oldSession.stopRunning() }
            captureSession = nil
            videoOutput = nil
            currentCameraInput = nil
            fileLog.log("📹 setupCaptureSession: queued stop of stale session")
        }

        // Snapshot the main-actor state the configuration needs, so the queue block
        // never reaches back onto the actor.
        let initialAngle = currentVideoRotationAngle()
        currentRotationAngle = initialAngle
        let mirrorFrontCamera = isUsingFrontCamera

        // 🔧 FIX (main-thread stall): everything below used to run right here on the
        // main runloop, including a synchronous `previousSession.stopRunning()`.
        // It now runs on `sessionQueue`; we only await the result.
        let result: CaptureSetupResult = await withCheckedContinuation { continuation in
            sessionQueue.async { [weak self] in
                guard let self else {
                    continuation.resume(returning: CaptureSetupResult(session: nil, output: nil, input: nil, errorMessage: "deallocated"))
                    return
                }
                continuation.resume(returning: self.configureCaptureSessionOnSessionQueue(initialAngle: initialAngle, mirrorFrontCamera: mirrorFrontCamera))
            }
        }

        guard let session = result.session else {
            fileLog.log("❌ setupCaptureSession: \(result.errorMessage ?? "unknown failure")")
            onError?(result.errorMessage ?? "Failed to access camera")
            return
        }

        // Publish on the main actor only.
        captureSession = session
        videoOutput = result.output
        currentCameraInput = result.input
        // 🔧 FIX: Track this session so the next call can force-stop it if needed
        VideoCallManager.lastActiveCaptureSession = session
        observeCaptureSession(session)
        fileLog.log("📹 setupCaptureSession: session configured, setting up encoder...")

        // Setup H.264 encoder
        await setupEncoder()
        fileLog.log("📹 setupCaptureSession: DONE")
    }

    /// 📹 __LOCAL_CAPTURE_WATCHDOG_2026_08_22__ AVCaptureSession dit tout ce qui
    /// lui arrive — et personne n'écoutait. Une erreur d'exécution, une capture
    /// interrompue par le système, un démarrage confirmé: les trois arrivaient en
    /// silence, et le journal d'appel ne gardait que « proceeding anyway ».
    ///
    /// L'interruption est celle qui compte: `AVCaptureSessionWasInterrupted` avec
    /// `videoDeviceNotAvailableInBackground` est exactement ce que reçoit une app
    /// qui décroche depuis CallKit avant d'être au premier plan. Sa fin
    /// (`interruptionEnded`) est le moment précis où relancer.
    private func observeCaptureSession(_ session: AVCaptureSession) {
        let center = NotificationCenter.default
        for name in [AVCaptureSession.runtimeErrorNotification,
                     AVCaptureSession.wasInterruptedNotification,
                     AVCaptureSession.interruptionEndedNotification,
                     AVCaptureSession.didStartRunningNotification,
                     AVCaptureSession.didStopRunningNotification] {
            center.removeObserver(self, name: name, object: nil)
        }
        center.addObserver(self, selector: #selector(captureSessionRuntimeError(_:)),
                           name: AVCaptureSession.runtimeErrorNotification, object: session)
        center.addObserver(self, selector: #selector(captureSessionWasInterrupted(_:)),
                           name: AVCaptureSession.wasInterruptedNotification, object: session)
        center.addObserver(self, selector: #selector(captureSessionInterruptionEnded(_:)),
                           name: AVCaptureSession.interruptionEndedNotification, object: session)
        center.addObserver(self, selector: #selector(captureSessionDidStartRunning(_:)),
                           name: AVCaptureSession.didStartRunningNotification, object: session)
        center.addObserver(self, selector: #selector(captureSessionDidStopRunning(_:)),
                           name: AVCaptureSession.didStopRunningNotification, object: session)
    }

    // ⚠️ `nonisolated`: AVCaptureSession poste ces notifications depuis SA file,
    // pas depuis la file principale. Un `@objc` d'une classe `@MainActor` appelé
    // depuis un autre fil est une violation d'isolation — on extrait donc les
    // valeurs (des entiers, une chaîne) et on repasse sur le MainActor. Passer
    // la `Notification` elle-même à travers ne serait pas `Sendable`.
    @objc nonisolated private func captureSessionRuntimeError(_ note: Notification) {
        let error = note.userInfo?[AVCaptureSessionErrorKey] as? NSError
        let described = error?.localizedDescription ?? "?"
        let code = error?.code ?? 0
        fileLog.log("❌ AVCaptureSession runtimeError: \(described) code=\(code)")
        // La session s'est arrêtée d'elle-même. Elle ne repartira pas seule; la
        // surveillance de démarrage est ce qui la reconstruit.
        Task { @MainActor [weak self] in
            guard let self, !self.isStopped, self.isSendingVideo else { return }
            self.armLocalCaptureWatchdog()
        }
    }

    @objc nonisolated private func captureSessionWasInterrupted(_ note: Notification) {
        let raw = note.userInfo?[AVCaptureSessionInterruptionReasonKey] as? Int ?? -1
        fileLog.log("⚠️ AVCaptureSession INTERROMPUE (raison=\(raw)) — 1=arrière-plan, 2=client prioritaire, 3=partagée, 4=multi-fenêtres")
    }

    @objc nonisolated private func captureSessionInterruptionEnded(_ note: Notification) {
        fileLog.log("📹 AVCaptureSession: fin d'interruption")
        // La session ne redémarre PAS toute seule après une interruption
        // d'arrière-plan: c'est à nous de la relancer, et de vérifier ensuite
        // qu'une image en sort vraiment.
        Task { @MainActor [weak self] in
            guard let self, !self.isStopped, self.isSendingVideo else { return }
            self.resumeCapture()
            self.armLocalCaptureWatchdog()
        }
    }

    @objc nonisolated private func captureSessionDidStartRunning(_ note: Notification) {
        fileLog.log("📹 AVCaptureSession: didStartRunning")
    }

    @objc nonisolated private func captureSessionDidStopRunning(_ note: Notification) {
        fileLog.log("📹 AVCaptureSession: didStopRunning")
    }

    /// MUST run on `sessionQueue`. Every call in here is a synchronous hardware
    /// round-trip: device discovery, input creation, begin/commitConfiguration, and
    /// the previous session's stopRunning + camera-release settle time.
    nonisolated private func configureCaptureSessionOnSessionQueue(initialAngle: CGFloat, mirrorFrontCamera: Bool) -> CaptureSetupResult {
        dispatchPrecondition(condition: .onQueue(sessionQueue))

        // 🔧 FIX: Force-stop the previous call's capture session if it still exists.
        // ARC may not have deallocated the old VideoCallManager yet, leaving the camera
        // locked. Blocking here is correct — this queue exists to absorb exactly this —
        // whereas the old code blocked the MAIN thread for stopRunning + 300 ms.
        if let previousSession = VideoCallManager.lastActiveCaptureSession {
            fileLog.log("📹 setupCaptureSession: stopping PREVIOUS call's capture session")
            previousSession.stopRunning()
            VideoCallManager.lastActiveCaptureSession = nil
            Thread.sleep(forTimeInterval: 0.3)  // let the camera hardware fully release
        }

        let session = AVCaptureSession()

        // 🔧 CRITICAL FIX: Prevent AVCaptureSession from auto-reconfiguring the audio session
        session.automaticallyConfiguresApplicationAudioSession = false

        // 📐 Use 720p preset for native 16:9 from camera.
        // With portrait orientation, camera gives 720x1280 (9:16).
        // Encoder scales this to configured resolution (e.g., 360x640 medium).
        session.sessionPreset = .hd1280x720

        guard let camera = getCamera(front: true),
              let input = try? AVCaptureDeviceInput(device: camera) else {
            return CaptureSetupResult(session: nil, output: nil, input: nil, errorMessage: "Failed to access camera")
        }
        fileLog.log("📹 setupCaptureSession: camera=\(camera.localizedName) input OK")

        // 🔧 FIX (iPhone 17 / iOS 26.4.1 PiP upside-down + peer sees black/sideways):
        // On iPhone 17, setting `videoRotationAngle` / `isVideoMirrored` on the
        // connection OUTSIDE a beginConfiguration/commitConfiguration block can
        // silently no-op — the buffer then arrives sensor-natural (1280×720
        // landscape) while the encoder is configured for 720×1280 portrait.
        // The encoder produces garbage NALs (one peer renders black, another
        // shows the frame 90° off), and the local self-view ends up rotated
        // the wrong way. switchCamera() already uses begin/commit; mirror that
        // here so the orientation settings always stick on first setup too.
        session.beginConfiguration()

        var addedInput: AVCaptureDeviceInput?
        if session.canAddInput(input) {
            session.addInput(input)
            addedInput = input
        }

        let output = AVCaptureVideoDataOutput()
        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]
        // Delegate delivery stays on `videoQueue` — deliberately NOT `sessionQueue`,
        // which owns stopRunning() and would then wait on itself.
        output.setSampleBufferDelegate(self, queue: videoQueue)
        output.alwaysDiscardsLateVideoFrames = true

        var addedOutput: AVCaptureVideoDataOutput?
        if session.canAddOutput(output) {
            session.addOutput(output)
            addedOutput = output
        }

        // 📐 Set capture rotation angle to match current device orientation.
        // Orientation observer will dynamically update this + recreate encoder on rotation.
        if let connection = output.connection(with: .video) {
            if connection.isVideoRotationAngleSupported(initialAngle) {
                connection.videoRotationAngle = initialAngle
                fileLog.log("📹 setupCaptureSession: rotationAngle=\(initialAngle)° supported=YES")
            } else {
                fileLog.log("⚠️ setupCaptureSession: rotationAngle=\(initialAngle)° NOT supported on this device")
            }
            // Mirror front camera for natural selfie view
            if connection.isVideoMirroringSupported && mirrorFrontCamera {
                connection.isVideoMirrored = true
            }
        }

        session.commitConfiguration()

        return CaptureSetupResult(session: session, output: addedOutput, input: addedInput)
    }

    /// Capture rotation angle for the AVCaptureConnection.
    ///
    /// 🔧 FIX (iPhone 17 / iOS 26.4.1 local PiP rendered upside-down):
    /// OSHI's call UI is portrait-locked — there is no landscape video
    /// call mode in the product. Previous implementations tried to track
    /// either windowScene.interfaceOrientation OR UIDevice.current.orientation
    /// and pick a matching angle (90 / 180 / 270 / 0). Both readings turned
    /// out to be unreliable on iPhone 17 / iOS 26.4.1 at setupCaptureSession
    /// time (orientation observer not yet armed → sensors return .unknown,
    /// interfaceOrientation occasionally reports portraitUpsideDown for a
    /// frame after cold launch). Either mis-read locked the capture session
    /// to videoRotationAngle=270 → upside-down portrait buffer for the
    /// whole call.
    ///
    /// Since the UI is always portrait, the deterministic fix is to always
    /// return 90 (normal portrait). If we ever add landscape call support,
    /// re-introduce a dynamic read.
    private func currentVideoRotationAngle() -> CGFloat {
        return 90
    }

    private func startCapture() async {
        guard let session = captureSession else { return }

        // 🔧 FIX: Start the capture session and verify it's running.
        // For the SECOND call, the previous VideoCallManager's deinit may not have
        // fully released the camera yet (ARC timing). We retry up to 3 times with
        // increasing delays to give the old session time to release the camera resource.
        for attempt in 1...3 {
            let captureSessionRef = session
            // 🔧 FIX: start on `sessionQueue`, not the delegate queue — see sessionQueue.
            sessionQueue.async {
                captureSessionRef.startRunning()
            }

            // Brief wait for session to start
            let delay: UInt64 = attempt == 1 ? 200_000_000 : 500_000_000  // 200ms first, 500ms for retries
            try? await Task.sleep(nanoseconds: delay)

            if session.isRunning {
                isVideoEnabled = true
                isSendingVideo = true
                OshiLog.call.info("📹 VideoCallManager: Capture session started (attempt \(attempt))")
                fileLog.log("📹 startCapture: session running after attempt \(attempt)")
                break
            } else if attempt < 3 {
                fileLog.log("⚠️ startCapture: session not running after attempt \(attempt) — retrying...")
            } else {
                // Trois tentatives sans que la session tourne. On pose quand même
                // les drapeaux — l'UI doit montrer « caméra allumée », c'est ce
                // que l'utilisateur a demandé — mais ce n'est plus le point final:
                // `armLocalCaptureWatchdog()` vérifie ensuite qu'une image sort
                // vraiment de la caméra, et reconstruit la session sinon. Avant,
                // ce log était la DERNIÈRE chose qui se passait, et l'appareil
                // restait en « vidéo activée, aucune image » jusqu'à ce que
                // l'utilisateur trouve tout seul le geste arrêt/relance.
                isVideoEnabled = true
                isSendingVideo = true
                fileLog.log("⚠️ startCapture: session non confirmée après 3 tentatives — la surveillance de démarrage prend le relais")
            }
        }

        // Audio session is owned by VoiceCallManager.acceptCall() — do NOT reconfigure here.
        // automaticallyConfiguresApplicationAudioSession = false (set in setupCaptureSession)
        // prevents AVCaptureSession from touching audio. Reconfiguring with different
        // sample rate (48kHz vs 16kHz) and Bluetooth options caused crashes.
    }
    
    /// nonisolated: called from `sessionQueue` during configuration. Pure lookup.
    nonisolated private func getCamera(front: Bool) -> AVCaptureDevice? {
        let position: AVCaptureDevice.Position = front ? .front : .back
        return AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position)
    }
    
    // MARK: - H.264 Encoder Setup
    /// Creates encoder with dimensions matching current orientation.
    /// VideoQuality.resolution is portrait (w<h, e.g., 360x640).
    /// For landscape, width/height are swapped (640x360).
    private func setupEncoder() async {
        let isLandscape = (currentRotationAngle == 0 || currentRotationAngle == 180)  // 0=landscapeRight, 180=landscapeLeft
        let width: Int32
        let height: Int32
        if isLandscape {
            width = Int32(currentQuality.resolution.height)   // e.g., 640
            height = Int32(currentQuality.resolution.width)   // e.g., 360
        } else {
            width = Int32(currentQuality.resolution.width)    // e.g., 360
            height = Int32(currentQuality.resolution.height)  // e.g., 640
        }
        
        let encoderSpec: [String: Any] = [
            kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder as String: true
        ]
        
        var session: VTCompressionSession?
        let status = VTCompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            width: width,
            height: height,
            codecType: kCMVideoCodecType_H264,
            encoderSpecification: encoderSpec as CFDictionary,
            imageBufferAttributes: nil,
            compressedDataAllocator: nil,
            outputCallback: nil,
            refcon: nil,
            compressionSessionOut: &session
        )
        
        guard status == noErr, let compressionSession = session else {
            OshiLog.call.info("📹 VideoCallManager: Failed to create encoder: \(status)")
            return
        }
        
        // Configure encoder
        VTSessionSetProperty(compressionSession, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        // 🔧 FIX (2026-09-15, « pixélise quand on bouge »): MAIN profile instead of
        // BASELINE. Baseline uses CAVLC entropy coding; Main enables CABAC, which is
        // ~10–15 % more efficient at the SAME bitrate — the gain lands exactly on the
        // high-residual macroblocks that motion produces, so moving areas stop
        // breaking into blocks. No added latency: we still forbid frame reordering
        // (no B-frames), so it stays one-frame-in / one-frame-out for real-time.
        // Every H.264 decoder (VideoToolbox on iOS, MediaCodec on Android) reads the
        // profile from the SPS and handles Main universally, so this is safe across
        // platforms (Android encoder is being raised to Main in parallel to match).
        VTSessionSetProperty(compressionSession, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_H264_Main_AutoLevel)
        VTSessionSetProperty(compressionSession, key: kVTCompressionPropertyKey_H264EntropyMode, value: kVTH264EntropyMode_CABAC)
        VTSessionSetProperty(compressionSession, key: kVTCompressionPropertyKey_AverageBitRate, value: currentQuality.bitrate as CFNumber)
        // Keyframe every 1 second (30 frames at 30fps) for better motion quality
        VTSessionSetProperty(compressionSession, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: 30 as CFNumber)
        VTSessionSetProperty(compressionSession, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: 30 as CFNumber)
        VTSessionSetProperty(compressionSession, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)

        // Allow short-window peaks up to 2x the average so the encoder can
        // pour extra bits into high-motion frames. Without this, VTSession
        // uses a conservative implicit cap (~1.2x average) and motion turns
        // visibly blocky on the Android receiver — this is what caused the
        // "video pixelated when there's movement" symptom on the iPhone-→-
        // Android direction. Format = [maxBytes, intervalSeconds] pairs.
        // 🔧 (2026-09-15) raised 2.0x → 2.5x: a fast pan/turn is a sub-second burst
        // of high-residual frames; a 2.0x/1s cap still clipped the peak and the
        // motion frames came out blocky. 2.5x lets the encoder spend on the motion
        // and settle back within the same 1 s window (average bitrate is unchanged).
        let peakBytes = NSNumber(value: Double(currentQuality.bitrate) * 2.5 / 8.0)
        let dataRateLimits = [peakBytes, NSNumber(value: 1.0)] as CFArray
        VTSessionSetProperty(compressionSession, key: kVTCompressionPropertyKey_DataRateLimits, value: dataRateLimits)
        // Match Android: no B-frames (already implied by Baseline + AllowFrameReordering=false).
        // WHY: real-time calls — we want each captured frame on the wire
        // as fast as possible, even if that costs a touch of quality.
        // Previous value was kCFBooleanFalse (quality over speed) which
        // added ~1 frame of encoder look-ahead → ~33ms of one-sided delay.
        if #available(iOS 16.0, *) {
            VTSessionSetProperty(compressionSession, key: kVTCompressionPropertyKey_PrioritizeEncodingSpeedOverQuality, value: kCFBooleanTrue)
        }
        // Zero-latency mode: encoder emits each frame the moment it's
        // done, with no internal buffering for rate-control look-ahead.
        VTSessionSetProperty(compressionSession, key: kVTCompressionPropertyKey_MaxFrameDelayCount, value: 0 as CFNumber)
        
        VTCompressionSessionPrepareToEncodeFrames(compressionSession)

        self.compressionSession = compressionSession
        // 📡 ABR: encoder is fresh at ceiling bitrate — reset the tracker so the
        // next adapter tick re-applies the path-throttled value instead of
        // short-circuiting on the "already at this bitrate" guard.
        lastAppliedBitrate = currentQuality.bitrate
        fileLog.log("📹 H.264 encoder ready: \(width)x\(height) @ \(currentQuality.bitrate/1000)kbps (\(currentQuality.rawValue))")
        OshiLog.call.info("📹 VideoCallManager: H.264 encoder ready \(width)x\(height)")
    }
    
    private func updateEncoderSettings() {
        guard let session = compressionSession else { return }
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate, value: currentQuality.bitrate as CFNumber)
    }
    
    /// Apply a CGAffineTransform to the remote video display layer based on the
    /// rotation hint embedded in the peer's video flags byte. Android peers
    /// encode in sensor orientation (typically 90° off from a portrait device);
    /// this lets us rotate them upright at display time without round-tripping
    /// through the encoder.
    @MainActor private func applyRemoteVideoRotation(rotationCode: Int) {
        let radians: CGFloat
        switch rotationCode {
        case 1: radians = .pi / 2     // 90° CW
        case 2: radians = .pi         // 180°
        case 3: radians = -(.pi / 2)  // 270° CW (i.e. 90° CCW)
        default: radians = 0
        }
        let transform = CGAffineTransform(rotationAngle: radians)
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        displayLayer.setAffineTransform(transform)
        CATransaction.commit()
    }

    // MARK: - Video Processing with Effects
    // 📹 Nonisolated — CIFilter is thread-safe. Called from videoQueue capture callback.
    // Uses nonisolated mirrors (currentBlurMode, currentBlurIntensity) instead of @Published vars.
    nonisolated func processFrame(_ pixelBuffer: CVPixelBuffer) -> CVPixelBuffer {
        let mode = currentBlurMode
        guard mode != .none else { return pixelBuffer }

        let intensity = currentBlurIntensity
        var ciImage = CIImage(cvPixelBuffer: pixelBuffer)

        switch mode {
        case .none:
            break

        case .backgroundBlur:
            let blurRadius = intensity * 20
            if let filter = CIFilter(name: "CIGaussianBlur") {
                filter.setValue(ciImage, forKey: kCIInputImageKey)
                filter.setValue(blurRadius, forKey: kCIInputRadiusKey)
                if let output = filter.outputImage {
                    ciImage = output.cropped(to: ciImage.extent)
                }
            }

        case .fullBlur:
            let radius = intensity * 30
            if let filter = CIFilter(name: "CIGaussianBlur") {
                filter.setValue(ciImage, forKey: kCIInputImageKey)
                filter.setValue(radius, forKey: kCIInputRadiusKey)
                if let output = filter.outputImage {
                    ciImage = output.cropped(to: ciImage.extent)
                }
            }

        case .pixelate:
            let scale = 8 + (intensity * 24)
            if let filter = CIFilter(name: "CIPixellate") {
                filter.setValue(ciImage, forKey: kCIInputImageKey)
                filter.setValue(scale, forKey: kCIInputScaleKey)
                if let output = filter.outputImage {
                    ciImage = output.cropped(to: ciImage.extent)
                }
            }

        case .heavyPixelate:
            let scale = 32 + (intensity * 32)
            if let filter = CIFilter(name: "CIPixellate") {
                filter.setValue(ciImage, forKey: kCIInputImageKey)
                filter.setValue(scale, forKey: kCIInputScaleKey)
                if let output = filter.outputImage {
                    ciImage = output.cropped(to: ciImage.extent)
                }
            }

        case .ultraPixelate:
            let scale = 64 + (intensity * 64)
            if let filter = CIFilter(name: "CIPixellate") {
                filter.setValue(ciImage, forKey: kCIInputImageKey)
                filter.setValue(scale, forKey: kCIInputScaleKey)
                if let output = filter.outputImage {
                    ciImage = output.cropped(to: ciImage.extent)
                }
            }
        }

        // Render processed image back to pixel buffer
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        var newPixelBuffer: CVPixelBuffer?
        CVPixelBufferCreate(
            kCFAllocatorDefault, width, height,
            kCVPixelFormatType_32BGRA, nil, &newPixelBuffer
        )

        if let buffer = newPixelBuffer {
            ciContext.render(ciImage, to: buffer)
            return buffer
        }

        return pixelBuffer
    }
    
    // MARK: - Encoding
    nonisolated(unsafe) private var encodingStartTime: Date?

    /// Rotate a landscape capture buffer to portrait via CoreImage.
    /// `mirror=true` (front camera) rotates 90° clockwise; `mirror=false`
    /// (back camera) rotates 90° counter-clockwise. Output buffer dims
    /// are swapped (inH × inW) so the encoder sees correctly proportioned
    /// portrait input and never has to squash the image.
    nonisolated private func rotateBufferToPortrait(_ pixelBuffer: CVPixelBuffer, mirror: Bool) -> CVPixelBuffer? {
        let inW = CVPixelBufferGetWidth(pixelBuffer)
        let inH = CVPixelBufferGetHeight(pixelBuffer)
        let outW = inH
        let outH = inW

        let attrs: [String: Any] = [
            kCVPixelBufferIOSurfacePropertiesKey as String: [:]
        ]
        var newBuffer: CVPixelBuffer?
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            outW, outH,
            kCVPixelFormatType_32BGRA,
            attrs as CFDictionary,
            &newBuffer
        )
        guard status == kCVReturnSuccess, let buffer = newBuffer else { return nil }

        // CoreImage uses a y-up coordinate system. To produce a visually
        // 90° clockwise rotation, rotate by -π/2 then translate the result
        // back into [0, outW] × [0, outH].
        let angle: CGFloat = mirror ? -.pi / 2 : .pi / 2
        let rotated = CIImage(cvPixelBuffer: pixelBuffer)
            .transformed(by: CGAffineTransform(rotationAngle: angle))
        let translated = rotated.transformed(by: CGAffineTransform(
            translationX: -rotated.extent.minX,
            y: -rotated.extent.minY
        ))
        ciContext.render(translated, to: buffer)
        return buffer
    }

    // 🔧 FIX: nonisolated so it can be called directly from videoQueue capture callback
    // VTCompressionSession is thread-safe; no MainActor needed
    nonisolated func encodeFrame(_ pixelBuffer: CVPixelBuffer, timestamp: CMTime) {
        guard let session = compressionSession else { return }

        frameCount += 1
        if encodingStartTime == nil { encodingStartTime = Date() }

        // WHY: when AVCaptureConnection.videoRotationAngle silently fails
        // (iPhone 17 / iOS 26.4.1), the capture buffer arrives landscape
        // (1280×720) but the encoder is configured portrait (270×480).
        // VT then scales-and-squashes — the encoded frame is portrait
        // dimensions but contains a horizontally-compressed landscape
        // image. Rotating at the receiver fixes orientation but not the
        // squash, so the remote view shows a thin band. Pre-rotate the
        // buffer to portrait HERE in software so the encoder receives
        // upright input and produces a correctly proportioned portrait
        // frame; no rotation hint needed.
        let inW = CVPixelBufferGetWidth(pixelBuffer)
        let inH = CVPixelBufferGetHeight(pixelBuffer)
        var bufferToEncode = pixelBuffer
        if inW > inH {
            if let rotated = rotateBufferToPortrait(pixelBuffer, mirror: isUsingFrontCameraMirror) {
                bufferToEncode = rotated
            }
        }
        // After pre-rotation the buffer is portrait → receiver renders
        // with identity. Wire code 0 covers both the natively-portrait
        // and pre-rotated cases.
        pendingWireRotationCode = 0

        // 🔧 Keyframe forcing:
        // - First 5 seconds: keyframe every 0.5s (ensures receiver gets IDR quickly even if WS slow)
        // - After 5 seconds: keyframe every 3s (steady state — less bandwidth, less CPU)
        let timeSinceStart = Date().timeIntervalSince(encodingStartTime ?? Date())
        // 🛡️ While frozen for audio we want a fresh still ~every 2 s (the only
        // frames sendVideoPacket lets through), so tighten the encoder's forced
        // keyframe cadence to match — otherwise the steady-state 3 s interval
        // would leave the receiver on a stale still and waste P-frames the
        // sender just drops.
        let keyframeInterval: TimeInterval = {
            if isVideoFrozenForAudio { return frozenKeyframeIntervalSeconds }
            return timeSinceStart < 5.0 ? 0.5 : 3.0
        }()

        var properties: [String: Any]?
        if Date().timeIntervalSince(lastKeyFrameTime) > keyframeInterval {
            properties = [kVTEncodeFrameOptionKey_ForceKeyFrame as String: true]
            lastKeyFrameTime = Date()
        }
        
        var infoFlags = VTEncodeInfoFlags()
        
        let status = VTCompressionSessionEncodeFrame(
            session,
            imageBuffer: bufferToEncode,
            presentationTimeStamp: timestamp,
            duration: CMTime(value: 1, timescale: 30),
            frameProperties: properties as CFDictionary?,
            infoFlagsOut: &infoFlags
        ) { [weak self] status, _, sampleBuffer in
            guard status == noErr, let buffer = sampleBuffer else { return }
            // 🔧 FIX: Call directly — handleEncodedFrame is nonisolated
            // No MainActor dispatch needed. Runs on VT's internal callback thread.
            self?.handleEncodedFrame(buffer)
        }
        
        if status != noErr {
            OshiLog.call.info("📹 VideoCallManager: Encode error: \(status)")
            // __ENCODER_RECOVERY_2026_06_19__ -12903 kVTInvalidSessionErr /
            // -12905 kVTVideoEncoderMalfunctionErr leave the compression
            // session permanently dead — every later frame fails and the peer
            // sees black (observed on iPhone 17 Pro Max / iOS 26.x: 400+
            // consecutive -12903, zero frames sent). Tear it down and recreate
            // so outgoing video recovers. Nil-ing the session synchronously
            // makes subsequent frames no-op at the top guard until setupEncoder
            // rebuilds it, so only one recreation runs.
            if status == -12903 || status == -12905 {
                if let dead = compressionSession {
                    VTCompressionSessionInvalidate(dead)
                }
                compressionSession = nil
                Task { @MainActor [weak self] in
                    guard let self = self, self.compressionSession == nil else { return }
                    self.fileLog.log("📹 Encoder session invalid (\(status)) — recreating")
                    await self.setupEncoder()
                    self.forceKeyframe()
                }
            }
        }
    }
    
    nonisolated(unsafe) private var encodedFrameCount: UInt64 = 0

    // 🔧 FIX: nonisolated so it runs on VT callback thread without MainActor dispatch
    nonisolated private func handleEncodedFrame(_ sampleBuffer: CMSampleBuffer) {
        guard let dataBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { return }

        var length: Int = 0
        var dataPointer: UnsafeMutablePointer<Int8>?
        CMBlockBufferGetDataPointer(dataBuffer, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &length, dataPointerOut: &dataPointer)

        guard let pointer = dataPointer else { return }
        let data = Data(bytes: pointer, count: length)
        encodedFrameCount += 1

        // 🔧 ALWAYS extract and cache SPS/PPS from format description
        if let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer) {
            var spsSize: Int = 0
            var spsPointer: UnsafePointer<UInt8>?
            CMVideoFormatDescriptionGetH264ParameterSetAtIndex(formatDesc, parameterSetIndex: 0, parameterSetPointerOut: &spsPointer, parameterSetSizeOut: &spsSize, parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil)
            if let sps = spsPointer, spsSize > 0 {
                cachedSPS = Data(bytes: sps, count: spsSize)
            }

            var ppsSize: Int = 0
            var ppsPointer: UnsafePointer<UInt8>?
            CMVideoFormatDescriptionGetH264ParameterSetAtIndex(formatDesc, parameterSetIndex: 1, parameterSetPointerOut: &ppsPointer, parameterSetSizeOut: &ppsSize, parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil)
            if let pps = ppsPointer, ppsSize > 0 {
                cachedPPS = Data(bytes: pps, count: ppsSize)
            }
        }

        // Check if keyframe via attachments
        var isKeyFrame = false
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[CFString: Any]],
           let first = attachments.first {
            let notSync = first[kCMSampleAttachmentKey_NotSync] as? Bool ?? false
            let dependsOnOthers = first[kCMSampleAttachmentKey_DependsOnOthers] as? Bool ?? false
            isKeyFrame = !notSync || !dependsOnOthers
        } else {
            isKeyFrame = true
        }

        // 🔧 ROBUST: Multiple fallback keyframe detection methods:
        // 1. First frame is always a keyframe
        // 2. Every 60 frames (~2s at 30fps) force-include SPS/PPS
        // 3. Large frames (>8KB) are likely keyframes from encoder's forced interval
        // 4. Parse NAL type from AVCC data (type 5 = IDR)
        if encodedFrameCount == 1 {
            isKeyFrame = true
        }
        if !isKeyFrame && encodedFrameCount % 60 == 0 {
            isKeyFrame = true  // Periodic forced inclusion
        }
        if !isKeyFrame && data.count > 8000 {
            // Large frame - check NAL type for IDR
            if data.count > 5 {
                let nalType = data[4] & 0x1F
                if nalType == 5 { isKeyFrame = true }  // IDR slice
            }
        }

        // 🔧 CRITICAL FIX: ALWAYS include cached SPS/PPS with every packet
        // Only 13 bytes overhead per packet (9B SPS + 4B PPS), negligible vs 2-16KB frame data
        // This ensures the receiver can ALWAYS create a decoder when it detects an IDR frame,
        // even if the sender's keyframe flag is wrong (e.g., old code on remote device)
        let spsData: Data? = cachedSPS
        let ppsData: Data? = cachedPPS

        // 🔧 CRITICAL: Embed SPS/PPS as AVCC NAL units directly into frame data
        // Makes the stream self-describing - receiver can extract the CORRECT SPS/PPS
        // from the actual encoder, even if packet metadata fields are empty (old sender code)
        // Overhead: only 21 bytes/frame (4+9+4+4) vs 2-16KB frame = negligible
        var enrichedFrameData = data
        if let sps = cachedSPS, let pps = cachedPPS {
            var enriched = Data()
            var spsLength = UInt32(sps.count).bigEndian
            enriched.append(Data(bytes: &spsLength, count: 4))
            enriched.append(sps)
            var ppsLength = UInt32(pps.count).bigEndian
            enriched.append(Data(bytes: &ppsLength, count: 4))
            enriched.append(pps)
            enriched.append(data)
            enrichedFrameData = enriched
        }

        // 🔧 FIX (iPhone 14 receives iPhone 17's video 90° rotated):
        // The wire rotation hint is set in encodeFrame from the INPUT
        // pixel buffer dims. Reading the OUTPUT (encoded) frame dims
        // here is unreliable because the encoder squashes any input —
        // portrait or landscape — to its 360x640 portrait configuration,
        // so encoded dims always say "portrait, code 0" even when the
        // captured buffer was sideways.
        let wireRotationCode: UInt8 = pendingWireRotationCode

        // Build packet
        sendVideoPacket(frameData: enrichedFrameData, isKeyFrame: isKeyFrame, sps: spsData, pps: ppsData, wireRotationCode: wireRotationCode)
    }
    
    // MARK: - Packet Handling
    nonisolated(unsafe) private var videoTxCount: Int = 0

    // 🔧 FIX: nonisolated to run on encode callback thread
    nonisolated private func sendVideoPacket(frameData: Data, isKeyFrame: Bool, sps: Data?, pps: Data?, wireRotationCode: UInt8) {
        // 🔧 NEW: Check if sending is enabled (use nonisolated flag)
        guard isSendingEnabled else { return }

        // 🛡️ AUDIO-PRIORITY FREEZE: while the audio path is stressed we hand the
        // whole pipe to voice. Drop P-frames entirely; let only a periodic
        // keyframe through (~every 2 s) so the far end keeps a frozen last-frame
        // rather than a black tile, and so a fresh IDR lands the moment we thaw.
        // Audio is untouched throughout — it keeps its protected floor + FEC.
        if isVideoFrozenForAudio {
            let now = Date()
            let dueForKeyframe = now.timeIntervalSince(lastFrozenKeyframeSentAt) >= frozenKeyframeIntervalSeconds
            guard isKeyFrame && dueForKeyframe else {
                // Drop this frame (P-frame, or a keyframe that arrived too soon).
                return
            }
            lastFrozenKeyframeSentAt = now
        }

        videoTxCount += 1

        // 🔧 FIX: Lazy session key fetch — if setupVideoManager ran before key was established,
        // keep trying to get it from VoiceCallManager on each send attempt
        if sessionKey == nil {
            _ = getSessionKeyFromVoiceCall()
        }

        guard let key = sessionKey else {
            if videoTxCount <= 10 || videoTxCount % 30 == 0 {
                let msg = "❌ VideoCallManager: No session key for sending (frame #\(videoTxCount)) — will retry next frame"
                OshiLog.call.info("\(msg)")
                fileLog.log(msg)
            }
            return
        }

        // Log first few packets, keyframes, and every 30th
        if videoTxCount <= 5 || videoTxCount % 30 == 0 || isKeyFrame {
            let msg = "📹 VideoCallManager: TX #\(videoTxCount) keyframe:\(isKeyFrame) frame:\(frameData.count)B sps:\(sps?.count ?? 0)B pps:\(pps?.count ?? 0)B"
            OshiLog.call.info("\(msg)")
            fileLog.log(msg)
        }

        // Symmetric wire-format diagnostic. iOS VTCompressionSession emits AVCC
        // (4-byte big-endian length + NAL). nalType = (byte & 0x1F).
        // Cross-platform decoders can grep for `DIAG_VIDEO_TX | nalType=…` to verify.
        // Use a contiguous Data copy so 0-based indexing into bytes is safe even
        // when frameData is a slice with a non-zero startIndex.
        let frameBytes = [UInt8](frameData)
        let nalType: Int = {
            guard frameBytes.count >= 5 else { return -1 }
            // After enrichment we begin with [4B BE spsLen][SPS]. Walk length-prefixed
            // NALs and return the first slice/IDR type (1..5).
            var off = 0
            for _ in 0..<4 {
                guard off + 4 < frameBytes.count else { break }
                let len = (UInt32(frameBytes[off]) << 24) |
                          (UInt32(frameBytes[off + 1]) << 16) |
                          (UInt32(frameBytes[off + 2]) << 8) |
                          UInt32(frameBytes[off + 3])
                guard len > 0, Int(len) < frameBytes.count - off else { break }
                let t = Int(frameBytes[off + 4]) & 0x1F
                if (1...5).contains(t) { return t }
                off = off + 4 + Int(len)
            }
            return Int(frameBytes[4]) & 0x1F
        }()
        let diagMsg = "DIAG_VIDEO_TX | nalType=\(nalType) | size=\(frameData.count) | seq=\(videoTxCount) | isIDR=\(isKeyFrame) | wire=AVCC+enriched"
        OshiLog.call.info("\(diagMsg)")
        fileLog.log(diagMsg)

        // Build packet: [flags(1)][timestamp(8)][spsLen(2)][sps][ppsLen(2)][pps][frameData]
        var packet = Data()

        // 🔧 The wire rotation hint is computed by handleEncodedFrame from
        // the ACTUAL encoded frame dimensions + camera position (rather than
        // from currentRotationAngle, which can lie on iPhone 17 / iOS 26.4.1
        // when AVCaptureConnection.videoRotationAngle silently fails). See
        // handleEncodedFrame for the mapping. Receiver code → transform:
        //   code 0: identity        (buffer is upright portrait)
        //   code 1: rotate 90° CW   (front-cam sensor-natural landscape)
        //   code 2: rotate 180°
        //   code 3: rotate 90° CCW  (back-cam sensor-natural landscape)
        // Flags layout: bit 0 = keyframe, bits 1-2 = rotation code
        let flags: UInt8 = (isKeyFrame ? 1 : 0) | (wireRotationCode << 1)
        packet.append(flags)
        
        // Timestamp
        var timestamp = UInt64(Date().timeIntervalSince1970 * 1000)
        packet.append(Data(bytes: &timestamp, count: 8))
        
        // SPS (if keyframe)
        if let sps = sps {
            var spsLen = UInt16(sps.count)
            packet.append(Data(bytes: &spsLen, count: 2))
            packet.append(sps)
        } else {
            var spsLen: UInt16 = 0
            packet.append(Data(bytes: &spsLen, count: 2))
        }
        
        // PPS (if keyframe)
        if let pps = pps {
            var ppsLen = UInt16(pps.count)
            packet.append(Data(bytes: &ppsLen, count: 2))
            packet.append(pps)
        } else {
            var ppsLen: UInt16 = 0
            packet.append(Data(bytes: &ppsLen, count: 2))
        }
        
        // Frame data
        packet.append(frameData)

        // 📹 Application-level fragmentation (iOS↔Android cross-platform).
        // Split `packet` (the raw inner format) into ≤ MAX_FRAGMENT_PAYLOAD_BYTES
        // chunks. Each fragment is wrapped with [0x01][frame_id:2BE][idx:1][total:1]
        // and encrypted INDEPENDENTLY — single-fragment loss does NOT kill the
        // whole frame, single-fragment tampering only fails that fragment.
        let frameId = txFrameId & 0xFFFF
        txFrameId = (txFrameId + 1) & 0xFFFF

        // 📊 Measured keyframe size. The claim that "keyframes need IP fragmentation
        // over UDP" (the stated reason VoiceCallManager shotguns video down two
        // carriers) is testable only against a real number: every datagram we emit is
        // capped at MAX_FRAGMENT_PAYLOAD_BYTES + headers ≈ 1142 B, so no keyframe can
        // ever reach the IP layer as one oversized packet. Log the whole-frame size
        // and the fragment count it turns into so the next two-device call settles it.
        if isKeyFrame {
            diagLastKeyframeBytes = packet.count
            diagMaxKeyframeBytes = max(diagMaxKeyframeBytes, packet.count)
            let m = "DIAG_VIDEO_KEYFRAME_TX | frameId=\(frameId) | bytes=\(packet.count) | max=\(diagMaxKeyframeBytes) | fragments=\((packet.count + VideoCallManager.MAX_FRAGMENT_PAYLOAD_BYTES - 1) / VideoCallManager.MAX_FRAGMENT_PAYLOAD_BYTES) | datagramCap=\(VideoCallManager.MAX_FRAGMENT_PAYLOAD_BYTES + VideoCallManager.VIDEO_FRAG_HEADER_SIZE + 9 + 12 + 16)B | txNonce=\(videoTxNonceCount)"
            OshiLog.call.info("\(m)"); fileLog.log(m)
        }

        let payloadLen = packet.count
        let maxPay = VideoCallManager.MAX_FRAGMENT_PAYLOAD_BYTES
        let neededFragments = max(1, (payloadLen + maxPay - 1) / maxPay)

        // 🔧 FIX (silent truncation → guaranteed-corrupt frame at the peer):
        // this used to be `min(255, needed)`, and the emit loop below then wrote
        // only `total` fragments — so any packet over 255 × 1100 = 280,500 B lost
        // its TAIL. The receiver saw total=255, collected 255 fragments, declared
        // the frame COMPLETE and handed a truncated H.264 access unit to
        // VideoToolbox, which fails to decode it. `total_fragments` is a single wire
        // byte, so 255 is a hard protocol cap and nothing here can carry the rest.
        // Dropping the frame and forcing an IDR is strictly better: one skipped
        // frame instead of a corrupt one plus a decoder wedged on a broken
        // reference. (An oversized frame is almost always a keyframe emitted at a
        // bitrate spike; the forced IDR gives the encoder a clean restart point.)
        guard neededFragments <= 255 else {
            diagOversizeFramesDropped += 1
            let m = "DIAG_VIDEO_TX_OVERSIZE | frameId=\(frameId) | bytes=\(payloadLen) | neededFragments=\(neededFragments) | cap=255 | dropped=\(diagOversizeFramesDropped) — forcing IDR"
            OshiLog.call.info("\(m)"); fileLog.log(m)
            lastKeyFrameTime = .distantPast  // force an IDR on the next encode
            return
        }

        let total = neededFragments
        let hdrSize = VideoCallManager.VIDEO_FRAG_HEADER_SIZE
        let magic = VideoCallManager.VIDEO_FRAG_MAGIC

        for idx in 0..<total {
            let start = idx * maxPay
            let end = min(start + maxPay, payloadLen)
            var frag = Data(capacity: hdrSize + (end - start))
            frag.append(magic)
            frag.append(UInt8((frameId >> 8) & 0xFF))   // frame_id high (BE)
            frag.append(UInt8(frameId & 0xFF))          // frame_id low (BE)
            frag.append(UInt8(idx & 0xFF))              // fragment_index
            frag.append(UInt8(total & 0xFF))            // total_fragments
            frag.append(packet.subdata(in: start..<end))

            diagFragTxCount += 1
            if diagFragTxCount <= 20 || diagFragTxCount % 60 == 0 {
                let m = "DIAG_VIDEO_FRAG_TX | frameId=\(frameId) | total=\(total) | idx=\(idx) | size=\(frag.count)"
                OshiLog.call.info("\(m)")
                fileLog.log(m)
            }

            // Encrypt this fragment independently (its own AES-GCM nonce/tag)
            if let encrypted = encryptPacket(frag, key: key) {
                bytesSent += UInt64(encrypted.count)
                updateBandwidth()
                onVideoPacketReady?(encrypted)
            }
        }
    }
    
    nonisolated(unsafe) private var videoRxCount: Int = 0
    // nonisolated mirror of @Published isReceivingEnabled for background decode
    nonisolated(unsafe) private var receivingEnabledMirror: Bool = true

    /// nonisolated so the decode pipeline runs in background (required for PiP)
    nonisolated func receiveVideoPacket(_ data: Data) {
        // Check if receiving is enabled (use nonisolated mirror)
        guard receivingEnabledMirror else { return }

        videoRxCount += 1

        // Try to get session key if we don't have it — retry every packet
        if sessionKey == nil {
            _ = getSessionKeyFromVoiceCall()
        }

        guard let key = sessionKey else {
            if videoRxCount <= 10 || videoRxCount % 30 == 0 {
                let msg = "❌ VideoCallManager: No session key for decryption (packet #\(videoRxCount), size: \(data.count)) — will retry next packet"
                OshiLog.call.info("\(msg)")
                fileLog.log(msg)
            }
            return
        }

        guard let decrypted = decryptPacket(data, key: key) else {
            if videoRxCount <= 10 || videoRxCount % 50 == 0 {
                let msg = "❌ VideoCallManager: Decryption failed (packet #\(videoRxCount), size: \(data.count), key: \(key.count)B)"
                OshiLog.call.info("\(msg)")
                fileLog.log(msg)
            }
            return
        }

        bytesReceived += UInt64(data.count)
        // Update @Published on MainActor
        DispatchQueue.main.async { [weak self] in self?.isReceivingVideo = true }

        // 📹 Application-level fragment reassembly (iOS↔Android cross-platform).
        // Wire is [0x01][frame_id:2BE][idx:1][total:1][payload]. Receivers no longer
        // need legacy/non-fragmented detection logic — every frame uses this format.
        // Use a contiguous copy so 0-based indexing is always safe (decrypted is
        // typically already 0-based, but be defensive against Data subview surprises).
        let decryptedFlat: Data = Data(decrypted)
        let hdrSize = VideoCallManager.VIDEO_FRAG_HEADER_SIZE
        guard decryptedFlat.count > hdrSize, decryptedFlat[0] == VideoCallManager.VIDEO_FRAG_MAGIC else {
            if videoRxCount <= 5 {
                fileLog.log("❌ VideoCallManager: not a fragment packet (size=\(decryptedFlat.count), first=\(decryptedFlat.first.map { String(format: "0x%02X", $0) } ?? "nil"))")
            }
            return
        }

        let frameId = (Int(decryptedFlat[1]) << 8) | Int(decryptedFlat[2])
        let fragIdx = Int(decryptedFlat[3])
        let fragTotal = Int(decryptedFlat[4])
        guard fragTotal >= 1, fragIdx < fragTotal else {
            fileLog.log("❌ VideoCallManager: bad frag header frameId=\(frameId) idx=\(fragIdx) total=\(fragTotal)")
            return
        }
        let payload = decryptedFlat.subdata(in: hdrSize..<decryptedFlat.count)

        // Late-fragment drop using circular distance (UInt16 wrap-around safe).
        if rxLastCompletedFrameId >= 0 {
            let dist = (frameId - rxLastCompletedFrameId) & 0xFFFF
            if dist > 32768 {
                diagFragRxCount += 1
                if diagFragRxCount <= 20 || diagFragRxCount % 120 == 0 {
                    let m = "DIAG_VIDEO_FRAG_RX | frameId=\(frameId) | total=\(fragTotal) | got=- | reassembled=false (late, last=\(rxLastCompletedFrameId))"
                    OshiLog.call.info("\(m)"); fileLog.log(m)
                }
                return
            }
        }

        // Fast path: single-fragment frame
        diagFragRxCount += 1
        let assembled: Data
        if fragTotal == 1 {
            if diagFragRxCount <= 20 || diagFragRxCount % 60 == 0 {
                let m = "DIAG_VIDEO_FRAG_RX | frameId=\(frameId) | total=1 | got=1 | reassembled=true"
                OshiLog.call.info("\(m)"); fileLog.log(m)
            }
            noteFrameCompleted(frameId)
            assembled = payload
        } else {
            // Multi-fragment: store and check completion (locked)
            os_unfair_lock_lock(&reassemblyLock)
            // 🔧 FIX (the reassembly window was ONE frame): this loop used to drop
            // EVERY in-flight entry older than the incoming frame_id, so the first
            // fragment of frame N+1 destroyed all fragments of frame N already
            // collected. Fragments legitimately interleave across frames under UDP
            // reordering (and the TX path currently shotguns two carriers with
            // different latencies, which reorders by construction), so this threw
            // away frames that were about to complete. MAX_REASSEMBLY_ENTRIES = 8 was
            // dead code as a consequence, and every purge also fired
            // requestKeyframeFromPeer() below — meaning plain reordering, not loss,
            // was driving a stream of expensive IDR requests at the peer.
            // Now: age out ONLY entries more than MAX_REASSEMBLY_ENTRIES frames
            // behind the newest arrival. Those can no longer complete in time.
            var droppedIdr = false
            let window = VideoCallManager.MAX_REASSEMBLY_ENTRIES
            for oldId in reassemblyOrder {
                if oldId == frameId { continue }
                let dist = (frameId - oldId) & 0xFFFF
                if dist > window && dist <= 32768 {
                    reassemblyMap.removeValue(forKey: oldId)
                    droppedIdr = true
                }
            }
            if droppedIdr {
                reassemblyOrder.removeAll { reassemblyMap[$0] == nil }
            }

            let entry: FragReassembly
            if let existing = reassemblyMap[frameId] {
                entry = existing
                if entry.total != fragTotal {
                    reassemblyMap.removeValue(forKey: frameId)
                    reassemblyOrder.removeAll { $0 == frameId }
                    os_unfair_lock_unlock(&reassemblyLock)
                    fileLog.log("DIAG_VIDEO_FRAG_RX | frameId=\(frameId) | total mismatch (had=\(entry.total), now=\(fragTotal)) — dropping")
                    return
                }
            } else {
                entry = FragReassembly(total: fragTotal)
                reassemblyMap[frameId] = entry
                reassemblyOrder.append(frameId)
                // Cap reassembly map (drop oldest)
                while reassemblyOrder.count > VideoCallManager.MAX_REASSEMBLY_ENTRIES {
                    let oldestId = reassemblyOrder.removeFirst()
                    reassemblyMap.removeValue(forKey: oldestId)
                }
            }
            if entry.fragments[fragIdx] == nil {
                entry.fragments[fragIdx] = payload
                entry.receivedCount += 1
            }

            if diagFragRxCount <= 30 || diagFragRxCount % 60 == 0 {
                let m = "DIAG_VIDEO_FRAG_RX | frameId=\(frameId) | total=\(fragTotal) | got=\(entry.receivedCount) | reassembled=\(entry.receivedCount == fragTotal)"
                OshiLog.call.info("\(m)"); fileLog.log(m)
            }

            if entry.receivedCount != fragTotal {
                os_unfair_lock_unlock(&reassemblyLock)
                if droppedIdr {
                    // Old reassembly was unrecoverable — request peer keyframe.
                    requestKeyframeFromPeer()
                }
                return
            }
            // Concatenate in order
            var out = Data()
            for b in entry.fragments { if let b = b { out.append(b) } }
            reassemblyMap.removeValue(forKey: frameId)
            reassemblyOrder.removeAll { $0 == frameId }
            os_unfair_lock_unlock(&reassemblyLock)
            // ⚠️ Lock released FIRST: noteFrameCompleted takes `reassemblyLock`
            // itself (os_unfair_lock is not recursive) and can call out to
            // VoiceCallManager.sendKeyframeRequest.
            noteFrameCompleted(frameId)
            if droppedIdr {
                requestKeyframeFromPeer()
            }
            assembled = out
        }

        // Parse packet: [flags(1)][timestamp(8)][spsLen(2)][sps][ppsLen(2)][pps][frameData]
        let parseTarget = assembled
        guard parseTarget.count > 13 else {
            if videoRxCount <= 5 {
                fileLog.log("❌ VideoCallManager: Packet too small: \(parseTarget.count)B")
            }
            return
        }

        var offset = 0

        // Flags byte layout (cross-platform):
        //   bit 0    : keyframe
        //   bits 1-2 : clockwise rotation the display should apply (0/90/180/270 → 0/1/2/3)
        // Older Android builds didn't set bits 1-2; remoteRotationCode == 0
        // means "no rotation hint" → display untransformed (current behaviour).
        let flags = parseTarget[parseTarget.startIndex + offset]
        let isKeyFrame = (flags & 1) != 0
        let rotationCode = Int((flags >> 1) & 0x3)
        if rotationCode != lastRemoteRotationCode {
            lastRemoteRotationCode = rotationCode
            DispatchQueue.main.async { [weak self] in
                self?.applyRemoteVideoRotation(rotationCode: rotationCode)
            }
        }
        offset += 1

        // Timestamp
        offset += 8  // Skip timestamp (not used for direct decode)

        // SPS length and data — operate on a contiguous Data copy so 0-based offsets
        // are safe even if `parseTarget` came from a slice with a non-zero startIndex.
        let pt: Data = Data(parseTarget)

        // SPS length and data
        let spsLen = pt.subdata(in: offset..<(offset + 2)).withUnsafeBytes { $0.load(as: UInt16.self) }
        offset += 2
        var spsData: Data?
        if spsLen > 0 && (offset + Int(spsLen)) <= pt.count {
            spsData = pt.subdata(in: offset..<(offset + Int(spsLen)))
            offset += Int(spsLen)
        }

        // PPS length and data
        guard offset + 2 <= pt.count else { return }
        let ppsLen = pt.subdata(in: offset..<(offset + 2)).withUnsafeBytes { $0.load(as: UInt16.self) }
        offset += 2
        var ppsData: Data?
        if ppsLen > 0 && (offset + Int(ppsLen)) <= pt.count {
            ppsData = pt.subdata(in: offset..<(offset + Int(ppsLen)))
            offset += Int(ppsLen)
        }

        // Frame data
        guard offset < pt.count else { return }
        let frameData = pt.subdata(in: offset..<pt.count)

        // 🔧 Extract SPS/PPS embedded in frame data by new sender code
        let (embeddedSPS, embeddedPPS) = extractSPSPPSFromFrameData(frameData)

        // NAL type detection
        let detectedNALType = extractFirstNALType(from: frameData)
        let isIDR = (detectedNALType == 5)

        // Cache SPS/PPS from ALL sources (embedded > metadata)
        if let sps = spsData, !sps.isEmpty { receivedCachedSPS = sps }
        if let pps = ppsData, !pps.isEmpty { receivedCachedPPS = pps }
        if let sps = embeddedSPS, !sps.isEmpty { receivedCachedSPS = sps }
        if let pps = embeddedPPS, !pps.isEmpty { receivedCachedPPS = pps }

        // SPS/PPS priority: embedded > metadata > cached > local
        let bestSPS = embeddedSPS ?? spsData ?? receivedCachedSPS ?? cachedSPS
        let bestPPS = embeddedPPS ?? ppsData ?? receivedCachedPPS ?? cachedPPS

        // 🔧 Only strip embedded SPS/PPS NAL units (types 7, 8) from frame data
        // KEEP SEI (type 6) — decoder may need it for timing/recovery info
        let cleanedData = stripParameterSetNALs(from: frameData)

        // Logging — more frequent for first 20 frames to diagnose decode issues
        if videoRxCount <= 20 || videoRxCount % 50 == 0 || isKeyFrame || isIDR {
            let source = embeddedSPS != nil ? "embedded" : (spsData != nil ? "metadata" : (receivedCachedSPS != nil ? "cached" : "local"))
            let msg = "📹 RX #\(videoRxCount) key:\(isKeyFrame) IDR:\(isIDR) NAL:\(detectedNALType ?? 0) raw:\(frameData.count)B clean:\(cleanedData.count)B sps:\(bestSPS?.count ?? 0)B(\(source)) pps:\(bestPPS?.count ?? 0)B idr_ok:\(hasDecodedIDR) decoder:\(decompressionSession != nil)"
            OshiLog.call.info("\(msg)")
            fileLog.log(msg)
        }

        // Symmetric wire-format diagnostic (paired with DIAG_VIDEO_TX). Reports the
        // first slice/IDR NAL type so we can compare iOS-RX and Android-RX after a
        // bidirectional test call. decodeOk is set later from the decode callback.
        let diagRxMsg = "DIAG_VIDEO_RX | nalType=\(detectedNALType ?? 0) | size=\(cleanedData.count) | seq=\(videoRxCount) | decodeOk=pending | isIDR=\(isIDR)"
        OshiLog.call.info("\(diagRxMsg)")
        fileLog.log(diagRxMsg)

        // Create/recreate decoder when we have SPS/PPS and either:
        // (a) no decoder exists yet, or (b) consecutive failures >= 30, or
        // (c) first IDR before any successful decode
        let needsDecoder = decompressionSession == nil
        let isAnyKeyframe = isIDR || isKeyFrame
        // Read the serialised counter ONCE — re-reading it three times could observe
        // three different values now that the bump is properly synchronised.
        let failuresNow = consecutiveDecodeFailures
        let shouldRecreate = needsDecoder
            || (failuresNow > 0 && failuresNow % 30 == 0)
            || (isAnyKeyframe && !hasDecodedIDR)

        if shouldRecreate, let sps = bestSPS, let pps = bestPPS {
            let source = embeddedSPS != nil ? "embedded" : (spsData != nil ? "metadata" : (receivedCachedSPS != nil ? "cached" : "local-encoder"))
            let reason = needsDecoder ? "first" : (isIDR ? "IDR" : (isKeyFrame ? "flags-key" : "retry-\(failuresNow)"))
            fileLog.log("📹 SETUP: RX#\(videoRxCount) reason:\(reason) SPS:\(sps.count)B PPS:\(pps.count)B source:\(source)")
            setupDecoder(sps: sps, pps: pps)
            lastDecoderCreationRx = videoRxCount
            if isAnyKeyframe {
                resetDecodeFailures()
            }
        }

        // Try to decode EVERY frame — decoder handles errors gracefully
        if decompressionSession != nil && cleanedData.count > 4 {
            decodeFrame(cleanedData)
        } else if videoRxCount <= 5 || videoRxCount % 50 == 0 {
            fileLog.log("⏳ WAIT: RX#\(videoRxCount) no decoder yet (SPS:\(bestSPS?.count ?? 0) PPS:\(bestPPS?.count ?? 0))")
        }
    }

    /// Extract NAL unit types from AVCC-formatted H.264 data
    /// Priority: IDR (5) > slice (1-4) > everything else
    nonisolated private func extractFirstNALType(from data: Data) -> UInt8? {
        guard data.count > 5 else { return nil }
        var offset = 0
        var hasSlice = false
        var firstType: UInt8 = 0

        while offset + 4 < data.count {
            let nalLength = data.subdata(in: offset..<(offset + 4)).withUnsafeBytes {
                UInt32(bigEndian: $0.load(as: UInt32.self))
            }
            guard nalLength > 0, nalLength < UInt32(data.count - offset) else { break }
            let nalStart = offset + 4
            guard nalStart < data.count else { break }

            let nalType = data[nalStart] & 0x1F
            if firstType == 0 { firstType = nalType }
            if nalType == 5 { return 5 }  // IDR - highest priority, return immediately
            if nalType >= 1 && nalType <= 4 { hasSlice = true }

            offset = nalStart + Int(nalLength)
        }
        if hasSlice { return 1 }
        return firstType > 0 ? firstType : nil
    }

    /// Extract SPS (NAL type 7) and PPS (NAL type 8) from AVCC-formatted frame data
    /// New sender code embeds these as the first NAL units in every frame
    nonisolated private func extractSPSPPSFromFrameData(_ data: Data) -> (sps: Data?, pps: Data?) {
        guard data.count > 5 else { return (nil, nil) }
        var sps: Data?
        var pps: Data?
        var offset = 0

        while offset + 4 < data.count {
            let nalLength = data.subdata(in: offset..<(offset + 4)).withUnsafeBytes {
                UInt32(bigEndian: $0.load(as: UInt32.self))
            }
            guard nalLength > 0, nalLength < UInt32(data.count - offset) else { break }
            let nalStart = offset + 4
            let nalEnd = nalStart + Int(nalLength)
            guard nalEnd <= data.count else { break }

            let nalType = data[nalStart] & 0x1F
            if nalType == 7 { sps = data.subdata(in: nalStart..<nalEnd) }
            if nalType == 8 { pps = data.subdata(in: nalStart..<nalEnd) }

            // Stop scanning after finding both (they're at the start)
            if sps != nil && pps != nil { break }
            // Also stop if we hit a slice/IDR (SPS/PPS always come first)
            if nalType >= 1 && nalType <= 5 { break }

            offset = nalEnd
        }
        return (sps, pps)
    }

    /// Strip only parameter set NAL units (SPS type 7, PPS type 8) from AVCC data
    /// Keeps SEI (6), slice (1-4), IDR (5) — decoder needs these for proper decoding
    /// SPS/PPS are extracted separately and used for format description
    nonisolated private func stripParameterSetNALs(from data: Data) -> Data {
        guard data.count > 5 else { return data }
        var result = Data()
        var offset = 0

        while offset + 4 < data.count {
            let nalLength = data.subdata(in: offset..<(offset + 4)).withUnsafeBytes {
                UInt32(bigEndian: $0.load(as: UInt32.self))
            }
            guard nalLength > 0, nalLength < UInt32(data.count - offset) else { break }
            let nalStart = offset + 4
            let nalEnd = nalStart + Int(nalLength)
            guard nalEnd <= data.count else { break }

            let nalType = data[nalStart] & 0x1F
            // Strip ONLY SPS (7) and PPS (8) — keep everything else including SEI (6)
            if nalType != 7 && nalType != 8 {
                result.append(data.subdata(in: offset..<nalEnd))
            }

            offset = nalEnd
        }
        return result
    }

    // MARK: - Decoding (nonisolated for background PiP decode)
    nonisolated(unsafe) private var decodedFrameCount: Int = 0
    /// Snapshot of `decodedFrameCount` at the last health tick.
    nonisolated(unsafe) private var lastHealthFrameCount: Int = 0
    nonisolated(unsafe) private var successfulDecodes: Int = 0

    // ────────────────────────────────────────────────────────────────────────
    // 🔧 FIX (cross-thread read-modify-write race): `consecutiveDecodeFailures`
    // was a plain `nonisolated(unsafe) var Int` incremented from the VideoToolbox
    // decode thread (the synchronous VTDecompressionSessionDecodeFrame return path)
    // AND from the main queue (the async decode callback), with no synchronisation
    // at all — while gating BOTH decoder recreation and the PLI. A lost increment
    // silently disarms `== 2` (the first-loss keyframe request), and a torn
    // read/write can park the counter at a value that never satisfies `% 12` or
    // `% 30` again, permanently disabling recovery for the rest of the call.
    // Now serialised: one lock, and the bump RETURNS the post-increment value so
    // callers act on the value they actually produced rather than re-reading it.
    // ────────────────────────────────────────────────────────────────────────
    nonisolated(unsafe) private var _consecutiveDecodeFailures: Int = 0
    nonisolated(unsafe) private var decodeFailLock = os_unfair_lock()

    /// Guards the LIFETIME of `decompressionSession` against its USE.
    ///
    /// 🔴 FIX (2026-08-20) — THE 30-MINUTE CALL THAT ENDED WITH THE APP GONE.
    ///
    /// From the device log of the 2026-08-20 call with Marcus: at 18:21:45.593
    /// the peer sent `0x0F` (stop video), `stopVideo()` ran to completion at
    /// .614 — and the process was gone by .375 of the next second, relaunching
    /// straight into the peer's callback. No memory warning, no user action: the
    /// app died in the ~700 ms that follow a video teardown.
    ///
    /// That window is a race, and it is structural rather than incidental:
    ///
    ///   • `receiveVideoPacket` is `nonisolated` and gates only on
    ///     `receivingEnabledMirror`, which `stopVideo()` never clears. In-flight
    ///     frames from the peer therefore keep flowing straight into
    ///     `decodeFrame` for as long as the network takes to go quiet.
    ///   • `decodeFrame` reads `decompressionSession` and then hands it to
    ///     `VTDecompressionSessionDecodeFrame`.
    ///   • `stopVideo()` nils that property on the main actor and invalidates
    ///     the session from a global queue, and `setupDecoder` invalidates it
    ///     from the RX path.
    ///
    /// A decode that got past the `guard` a microsecond before the nil is then
    /// running against a session VideoToolbox is tearing down. Apple is explicit
    /// that `VTDecompressionSessionInvalidate` must not overlap outstanding
    /// decode calls; ARC keeping the handle alive does not help, because what is
    /// being destroyed is the decoder behind it.
    ///
    /// Every reader and every writer of the session now goes through this lock,
    /// so "in use" and "being destroyed" cannot overlap. The teardown still runs
    /// off the main thread — it waits for asynchronous frames, which can block —
    /// so nothing here can stall the UI.
    nonisolated(unsafe) private var decoderLock = os_unfair_lock()

    nonisolated private var consecutiveDecodeFailures: Int {
        os_unfair_lock_lock(&decodeFailLock)
        defer { os_unfair_lock_unlock(&decodeFailLock) }
        return _consecutiveDecodeFailures
    }

    @discardableResult
    nonisolated private func bumpDecodeFailures() -> Int {
        os_unfair_lock_lock(&decodeFailLock)
        _consecutiveDecodeFailures += 1
        let result = _consecutiveDecodeFailures
        os_unfair_lock_unlock(&decodeFailLock)
        return result
    }

    nonisolated private func resetDecodeFailures() {
        os_unfair_lock_lock(&decodeFailLock)
        _consecutiveDecodeFailures = 0
        os_unfair_lock_unlock(&decodeFailLock)
    }
    nonisolated(unsafe) private var lastDecoderCreationRx: Int = 0

    nonisolated private func decodeFrame(_ data: Data) {
        // Held across the VTDecompressionSessionDecodeFrame call, not just
        // across the read: the point is that teardown cannot start while a
        // decode is in flight. See `decoderLock`.
        os_unfair_lock_lock(&decoderLock)
        defer { os_unfair_lock_unlock(&decoderLock) }
        guard let session = decompressionSession else {
            if decodedFrameCount == 0 || videoRxCount <= 5 || videoRxCount % 50 == 0 {
                let msg = "📹 VideoCallManager: No decoder session (waiting for keyframe) rx:#\(videoRxCount)"
                OshiLog.call.info("\(msg)")
                fileLog.log(msg)
            }
            return
        }

        guard let formatDesc = decoderFormatDescription else {
            fileLog.log("📹 VideoCallManager: No format description for decoding")
            return
        }

        let dataCopy = Data(data)
        var blockBuffer: CMBlockBuffer?
        let dataCount = dataCopy.count

        dataCopy.withUnsafeBytes { rawBuffer in
            guard let bytes = rawBuffer.baseAddress else { return }
            var tempBuffer: CMBlockBuffer?
            let status1 = CMBlockBufferCreateWithMemoryBlock(
                allocator: kCFAllocatorDefault,
                memoryBlock: nil,
                blockLength: dataCount,
                blockAllocator: kCFAllocatorDefault,
                customBlockSource: nil,
                offsetToData: 0,
                dataLength: dataCount,
                flags: 0,
                blockBufferOut: &tempBuffer
            )
            guard status1 == noErr, let tmp = tempBuffer else { return }

            let status2 = CMBlockBufferReplaceDataBytes(
                with: bytes,
                blockBuffer: tmp,
                offsetIntoDestination: 0,
                dataLength: dataCount
            )
            if status2 == noErr {
                blockBuffer = tmp
            }
        }

        guard let buffer = blockBuffer else { return }

        var sampleBuffer: CMSampleBuffer?
        var sampleSize = dataCount

        let status = CMSampleBufferCreate(
            allocator: kCFAllocatorDefault,
            dataBuffer: buffer,
            dataReady: true,
            makeDataReadyCallback: nil,
            refcon: nil,
            formatDescription: formatDesc,
            sampleCount: 1,
            sampleTimingEntryCount: 0,
            sampleTimingArray: nil,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sampleBuffer
        )

        guard status == noErr, let sample = sampleBuffer else {
            if decodedFrameCount <= 20 || videoRxCount % 50 == 0 {
                fileLog.log("❌ DECODE: CMSampleBufferCreate failed:\(status) size:\(dataCount) rx:#\(videoRxCount)")
            }
            bumpDecodeFailures()
            return
        }

        decodedFrameCount += 1
        let currentCount = decodedFrameCount
        var infoFlags = VTDecodeInfoFlags()
        let decodeStatus = VTDecompressionSessionDecodeFrame(
            session,
            sampleBuffer: sample,
            flags: [._EnableAsynchronousDecompression],
            infoFlagsOut: &infoFlags
        ) { [weak self] status, _, imageBuffer, _, _ in
            guard status == noErr, let pixelBuffer = imageBuffer else {
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    // Single atomic bump; every decision below uses the value THIS
                    // callback produced, so a concurrent bump from the decode thread
                    // can no longer make the `== 2` / `% 12` gates miss.
                    let failures = self.bumpDecodeFailures()
                    if failures <= 3 || failures % 50 == 0 {
                        self.fileLog.log("❌ DECODE: Frame #\(currentCount) error:\(status) failures:\(failures)")
                    }
                    // 📹 Fast keyframe recovery (WhatsApp/WebRTC-style PLI): ask the
                    // peer for a fresh keyframe on the FIRST loss (≥2 failures ≈ 66ms),
                    // then retry every ~12 frames (~0.4s) while still corrupt — instead
                    // of waiting 5 failures then only every ~1s. On a lossy link (e.g.
                    // China) this clears the blocky macroblock corruption seen on motion
                    // far sooner. The % 12 modulo rate-limits so we don't storm the encoder.
                    if failures == 2 || failures % 12 == 0 {
                        self.fileLog.log("📹 Requesting keyframe from peer (failures:\(failures))")
                        // Routed through requestKeyframeFromPeer so decode-driven and
                        // loss-driven PLIs land in the same DIAG_VIDEO_IDR_REQ cadence.
                        self.requestKeyframeFromPeer()
                    }
                }
                return
            }

            // 📺 Enqueue decoded frame to AVSampleBufferDisplayLayer (for PiP + display)
            if let layer = self?.displayLayer, layer.status != .failed {
                var fmtDesc: CMVideoFormatDescription?
                CMVideoFormatDescriptionCreateForImageBuffer(
                    allocator: kCFAllocatorDefault,
                    imageBuffer: pixelBuffer,
                    formatDescriptionOut: &fmtDesc
                )
                if let fmt = fmtDesc {
                    var timing = CMSampleTimingInfo(
                        duration: CMTime(value: 1, timescale: 30),
                        presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()),
                        decodeTimeStamp: .invalid
                    )
                    var sb: CMSampleBuffer?
                    CMSampleBufferCreateReadyWithImageBuffer(
                        allocator: kCFAllocatorDefault,
                        imageBuffer: pixelBuffer,
                        formatDescription: fmt,
                        sampleTiming: &timing,
                        sampleBufferOut: &sb
                    )
                    if let sb = sb {
                        // WHY: in real-time calls we never want to "catch up" —
                        // a queued frame is already behind the camera. If the
                        // display layer's internal buffer is full, flush stale
                        // frames before enqueueing the latest. Keeps perceived
                        // delay bounded by 1 frame instead of growing with the
                        // burstiness of arrival from VPS WebSocket.
                        if !layer.isReadyForMoreMediaData {
                            layer.flush()
                        }
                        layer.enqueue(sb)
                    }
                }
            }

            // Track decode success on MainActor
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.resetDecodeFailures()
                self.successfulDecodes += 1
                self.hasDecodedIDR = true
                self.isReceivingVideo = true
                if self.successfulDecodes <= 10 || self.successfulDecodes % 100 == 0 {
                    self.fileLog.log("📹 DECODED #\(self.successfulDecodes) ✅ rx:#\(self.videoRxCount)")
                    self.fileLog.log("DIAG_VIDEO_RX | nalType=decoded | size=- | seq=\(self.successfulDecodes) | decodeOk=true | isIDR=-")
                }
            }
        }

        if decodeStatus != noErr {
            let failures = bumpDecodeFailures()
            if failures <= 3 || failures % 50 == 0 {
                fileLog.log("❌ DECODE: VTDecodeFrame error:\(decodeStatus) frame#\(decodedFrameCount) failures:\(failures) rx:#\(videoRxCount)")
            }
        }
    }
    
    nonisolated private func setupDecoder(sps: Data, pps: Data) {
        // 🔧 FIX: Guard against empty SPS/PPS data to prevent crash
        guard !sps.isEmpty, !pps.isEmpty else {
            let msg = "❌ VideoCallManager: Cannot setup decoder - SPS or PPS is empty"
            OshiLog.call.info("\(msg)")
            fileLog.log(msg)
            return
        }
        fileLog.log("📹 SETUP DECODER: SPS=\(sps.count)B PPS=\(pps.count)B")

        // Invalidate old session — under `decoderLock`, because this runs on the
        // RX path and a decode of the previous frame may still be inside
        // VTDecompressionSessionDecodeFrame on another transport's queue.
        os_unfair_lock_lock(&decoderLock)
        if let old = decompressionSession {
            decompressionSession = nil
            VTDecompressionSessionWaitForAsynchronousFrames(old)
            VTDecompressionSessionInvalidate(old)
        }
        os_unfair_lock_unlock(&decoderLock)

        // Create format description
        var formatDesc: CMVideoFormatDescription?

        // Need to create these outside the closure
        sps.withUnsafeBytes { spsBuffer in
            pps.withUnsafeBytes { ppsBuffer in
                // 🔧 FIX: Safely unwrap base addresses to prevent crash
                guard let spsBase = spsBuffer.baseAddress,
                      let ppsBase = ppsBuffer.baseAddress else {
                    OshiLog.call.info("❌ VideoCallManager: Invalid SPS/PPS buffer")
                    return
                }

                let spsPointer = spsBase.assumingMemoryBound(to: UInt8.self)
                let ppsPointer = ppsBase.assumingMemoryBound(to: UInt8.self)

                var pointers: [UnsafePointer<UInt8>] = [spsPointer, ppsPointer]
                var sizes = [sps.count, pps.count]

                CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault,
                    parameterSetCount: 2,
                    parameterSetPointers: &pointers,
                    parameterSetSizes: &sizes,
                    nalUnitHeaderLength: 4,
                    formatDescriptionOut: &formatDesc
                )
            }
        }
        
        guard let format = formatDesc else {
            OshiLog.call.info("📹 VideoCallManager: Failed to create format description")
            return
        }

        // 🔧 FIX: Store format description for use in decodeFrame
        self.decoderFormatDescription = format
        
        // Create decompression session
        let decoderSpec: [String: Any] = [
            kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder as String: true
        ]
        
        let destAttrs: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]
        
        var session: VTDecompressionSession?
        let status = VTDecompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            formatDescription: format,
            decoderSpecification: decoderSpec as CFDictionary,
            imageBufferAttributes: destAttrs as CFDictionary,
            outputCallback: nil,
            decompressionSessionOut: &session
        )
        
        if status == noErr {
            // Configure BEFORE publishing: once it is visible to `decodeFrame`
            // another queue may be decoding into it.
            if let s = session, #available(iOS 16.0, *) {
                VTSessionSetProperty(s, key: kVTDecompressionPropertyKey_RealTime, value: kCFBooleanTrue)
            }
            os_unfair_lock_lock(&decoderLock)
            decompressionSession = session
            os_unfair_lock_unlock(&decoderLock)
            OshiLog.call.info("📹 VideoCallManager: Decoder session created")
            fileLog.log("✅ DECODER CREATED successfully")
        } else {
            OshiLog.call.info("📹 VideoCallManager: Failed to create decoder: \(status)")
            fileLog.log("❌ DECODER CREATION FAILED: \(status)")
        }
    }
    
    // MARK: - Encryption
    /// 📹 Ask peer to send a fresh IDR keyframe.
    /// Called when reassembly GC drops an in-flight frame (its IDR/P-frame is
    /// unrecoverable). Uses the existing requestKeyframe call-control packet.
    /// Same call site as the post-decode-failure path at line ~1750.
    nonisolated private func requestKeyframeFromPeer() {
        // 📊 IDR-request cadence. VoiceCallManager.sendKeyframeRequest rate-limits to
        // one request per 2 s and returns silently when it swallows one, so the
        // ATTEMPT rate is only visible from this side. A large gap between
        // `requests` here and `📹 Sending requestKeyframe` lines in the same log is
        // the signature of PLIs being capped at 0.5/s while frames are being lost
        // far faster than that.
        diagKeyframeRequestsSent += 1
        let now = Date()
        let sinceLast = now.timeIntervalSince(diagLastKeyframeRequestAt)
        diagLastKeyframeRequestAt = now
        if diagKeyframeRequestsSent <= 10 || diagKeyframeRequestsSent % 20 == 0 {
            let m = "DIAG_VIDEO_IDR_REQ | requests=\(diagKeyframeRequestsSent) | sinceLast=\(String(format: "%.2f", min(sinceLast, 999)))s"
            OshiLog.call.info("\(m)"); fileLog.log(m)
        }
        VoiceCallManager.shared.sendKeyframeRequest()
    }

    nonisolated private func encryptPacket(_ data: Data, key: Data) -> Data? {
        guard key.count == 32 else { return nil }

        do {
            let symmetricKey = SymmetricKey(data: key)

            // 🔐 [salt(4)][counter BE(8)] with the direction bit in the salt MSB and
            // the video-domain bit in the counter MSB — see the block comment on
            // `_videoTxSalt`. Built atomically so two encode threads can never be
            // handed the same counter.
            let nonceData = nextVideoNonceBytes()

            let nonce = try AES.GCM.Nonce(data: nonceData)
            let sealed = try AES.GCM.seal(data, using: symmetricKey, nonce: nonce)

            // Return: nonce + ciphertext + tag
            var result = nonceData
            result.append(sealed.ciphertext)
            result.append(sealed.tag)

            return result
        } catch {
            OshiLog.call.info("📹 VideoCallManager: Encryption error: \(error)")
            return nil
        }
    }
    
    nonisolated private func decryptPacket(_ data: Data, key: Data) -> Data? {
        guard key.count == 32, data.count > 28 else { return nil }
        
        do {
            let symmetricKey = SymmetricKey(data: key)
            
            let nonceData = data.prefix(12)
            let ciphertext = data.dropFirst(12).dropLast(16)
            let tag = data.suffix(16)
            
            let nonce = try AES.GCM.Nonce(data: nonceData)
            let sealedBox = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)

            let plaintext = try AES.GCM.open(sealedBox, using: symmetricKey)

            // 🔐 Anti-replay AFTER the tag verifies, so forged nonces can't poison the
            // window. A duplicate here is either a genuine replay or TX-side
            // duplication; either way the second copy must not be reassembled or
            // decoded twice.
            //
            // 🔧 CORRECTION (2026-07-27): this comment used to claim the receiver
            // dedup "does not exist anywhere". That is wrong. `rxDedupSeen` in
            // VoiceCallManager IS real and IS read, keyed
            // `(UInt64(packetType) << 56) | seq` with a 2 s window — but it sits
            // inside `receiveAudio`, and the video ingress points bypass it: the
            // P2P path branches to `handleVideoPacket` on the 0xF1 type byte
            // before reaching it, and the WS/HTTP/UDP-relay paths call
            // `onVideoPacketReceived` directly. Only mesh-carried video is deduped
            // by seq. So this window is the ONLY thing standing between a
            // duplicated fragment and a double reassembly — keep it on.
            // As of the same date `sendVideoPacketInternal` nominates ONE carrier,
            // so in steady state this should now drop ~0.
            if VideoCallManager.replayProtectionEnabled, isReplayedNonce(Data(nonceData)) { return nil }

            return plaintext
        } catch {
            OshiLog.call.info("📹 VideoCallManager: Decryption error: \(error)")
            return nil
        }
    }
    
    // MARK: - Bandwidth Tracking
    nonisolated private func updateBandwidth() {
        let now = Date()
        let elapsed = now.timeIntervalSince(lastBandwidthUpdate)
        
        if elapsed >= 1.0 {
            let outBw = Double(bytesSent) * 8 / elapsed / 1000 // kbps
            let inBw = Double(bytesReceived) * 8 / elapsed / 1000
            
            // 🔧 FIX: Update @Published properties on main thread
            Task { @MainActor in
                self.outgoingBandwidth = outBw
                self.incomingBandwidth = inBw
            }
            
            bytesSent = 0
            bytesReceived = 0
            lastBandwidthUpdate = now
        }
    }
}

// MARK: - AVCaptureVideoDataOutputSampleBufferDelegate
extension VideoCallManager: AVCaptureVideoDataOutputSampleBufferDelegate {
    nonisolated func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        // 🔧 FIX: Early exit if video was stopped — prevents zombie frame processing
        // after stopVideo() while capture session finishes shutting down
        guard !self.isStopped else { return }

        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        // 📹 __LOCAL_CAPTURE_WATCHDOG_2026_08_22__ La seule preuve que la caméra
        // débite vraiment. `captureSession.isRunning` et `isVideoEnabled` sont
        // tous les deux des affirmations de l'app sur elle-même; ceci est une
        // image, et c'est ce que la surveillance de démarrage lit.
        self.capturedFrameCount &+= 1

        let timestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)

        // 📹 Apply video effects FIRST — affects both local preview AND encoded output
        // processFrame is nonisolated (CIFilter is thread-safe), uses currentBlurMode mirror
        let finalBuffer = self.processFrame(pixelBuffer)

        // 🔧 FIX: Throttle local preview rendering to ~15fps on background renderQueue
        // GPU-intensive ciContext.createCGImage stays OFF MainActor
        // Shows the effect-applied frame so user sees their own blur/pixelation
        let now = CFAbsoluteTimeGetCurrent()
        let shouldUpdatePreview = (now - self.lastLocalRenderTime) > 0.067  // ~15fps

        if shouldUpdatePreview {
            // __PREVIEW_READBACK_2026_08_26__ Réduire AVANT `createCGImage`.
            //
            // `createCGImage` ne se contente pas de rendre sur le GPU: il
            // RAPATRIE le résultat en mémoire CPU. C'est la seule opération du
            // pipeline vidéo qui traverse la barrière GPU→CPU, et elle était
            // faite sur la trame ENTIÈRE — 720×1280 en 32 bits — quinze fois
            // par seconde. Soit 55 Mo/s de rapatriement, plus le renvoi vers le
            // GPU que SwiftUI fait ensuite pour composer l'image.
            //
            // Or cette image n'est affichée QUE dans la vignette de 90×160
            // points (`VideoCallView`), c'est-à-dire 270×480 pixels sur un
            // écran ×3. On rapatriait 7,1 fois plus de pixels que ce que
            // l'écran peut montrer.
            //
            // Le facteur d'échelle est calculé pour que le GRAND côté tombe à
            // `previewTargetLongEdge`, quelle que soit l'orientation dans
            // laquelle la caméra a livré la trame (la détection paysage de la
            // vue lit `width > height`, qu'une homothétie préserve). Jamais
            // d'agrandissement: `min(1, …)` laisse passer telle quelle une
            // trame déjà plus petite que la cible.
            let ciImage = CIImage(cvPixelBuffer: finalBuffer)
            let previewImage = VideoCallManager.previewScaled(ciImage)
            self.renderQueue.async { [weak self] in
                guard let self = self else { return }
                self.lastLocalRenderTime = CFAbsoluteTimeGetCurrent()
                if let cgImage = self.ciContext.createCGImage(previewImage, from: previewImage.extent) {
                    self.diagPreviewRendered &+= 1
                    DispatchQueue.main.async {
                        self.localVideoImage = cgImage
                    }
                } else {
                    // __PREVIEW_NIL_IS_SILENT_2026_08_27__ `createCGImage` qui rend
                    // `nil` ne lève rien: la vignette garde sa dernière image, ou
                    // reste vide, et RIEN ne le dit. Signalé le 2026-08-27:
                    // « je ne vois plus que je partage ma vidéo » — alors que le
                    // pair, lui, la recevait, donc les trames étaient bien
                    // capturées et encodées. Une panne d'aperçu qui ne laisse pas
                    // de trace est indiscernable d'une caméra morte.
                    self.diagPreviewFailed &+= 1
                    if self.diagPreviewFailed <= 3 || self.diagPreviewFailed % 60 == 0 {
                        let m = "DIAG_PREVIEW_NIL | failed=\(self.diagPreviewFailed) | rendered=\(self.diagPreviewRendered) | extent=\(Int(previewImage.extent.width))x\(Int(previewImage.extent.height))"
                        OshiLog.call.info("\(m)"); self.fileLog.log(m)
                    }
                }
            }
        }

        // 🔧 FIX: Encode directly on videoQueue — NO MainActor dispatch
        let captureCount = self.encodedFrameCount + 1
        if captureCount <= 3 || captureCount % 300 == 0 {
            let w = CVPixelBufferGetWidth(finalBuffer)
            let h = CVPixelBufferGetHeight(finalBuffer)
            self.fileLog.log("📹 CAPTURE frame#\(captureCount) \(w)x\(h) blur:\(self.currentBlurMode.rawValue)")
        }

        self.encodeFrame(finalBuffer, timestamp: timestamp)
    }

    // MARK: - 📺 PiP (Picture-in-Picture)

    /// Set up PiP controller — call AFTER the displayLayer is in the view hierarchy
    func setupPiP() {
        #if os(iOS)
        guard AVPictureInPictureController.isPictureInPictureSupported() else {
            fileLog.log("📺 PiP: not supported on this device")
            return
        }
        // Target mode: video-call PiP once the on-screen source view exists,
        // else the sample-buffer fallback. Keep an existing controller only if it
        // is already in the desired mode; otherwise rebuild so a controller created
        // before the source view was ready gets UPGRADED to the video-call PiP the
        // moment RemoteVideoLayerView calls back with the source.
        let wantVideoCall = useVideoCallPiP && pipSourceView != nil
        if pipController != nil {
            if pipUsesVideoCall == wantVideoCall {
                fileLog.log("📺 PiP: controller already in desired mode (videoCall=\(wantVideoCall))")
                return
            }
            fileLog.log("📺 PiP: rebuilding controller to upgrade mode → videoCall=\(wantVideoCall)")
            teardownPiP()
        }
        pipUsesVideoCall = wantVideoCall

        displayLayer.videoGravity = .resizeAspect

        let delegate = PiPPlaybackDelegate()
        self.pipPlaybackDelegate = delegate

        // 📺 Preferred path: the VIDEO-CALL PiP (no player chrome). Needs the
        // on-screen source view (set by RemoteVideoLayerView once it renders) and
        // the content VC. If either isn't ready, or the flag is off, fall back to
        // the sample-buffer PiP so PiP still works — just with the old player look.
        if useVideoCallPiP, let source = pipSourceView {
            let vc = ensurePiPVideoCallController()
            let contentSource = AVPictureInPictureController.ContentSource(
                activeVideoCallSourceView: source,
                contentViewController: vc
            )
            let controller = AVPictureInPictureController(contentSource: contentSource)
            controller.delegate = delegate
            controller.canStartPictureInPictureAutomaticallyFromInline = true
            self.pipController = controller
            fileLog.log("📺 PiP: VIDEO-CALL controller created (no player chrome), possible=\(controller.isPictureInPicturePossible)")
            return
        }

        let contentSource = AVPictureInPictureController.ContentSource(
            sampleBufferDisplayLayer: displayLayer,
            playbackDelegate: delegate
        )
        let controller = AVPictureInPictureController(contentSource: contentSource)
        controller.delegate = delegate
        // Hide playback controls (not a media player — just a live video call)
        controller.requiresLinearPlayback = true
        if #available(iOS 16.0, *) {
            controller.canStartPictureInPictureAutomaticallyFromInline = true
        }
        self.pipController = controller

        fileLog.log("📺 PiP: sample-buffer controller created (fallback, source=\(pipSourceView != nil) flag=\(useVideoCallPiP)), possible=\(controller.isPictureInPicturePossible)")
        #endif
    }

    /// Start PiP (called when app goes to background)
    func startPiP() {
        #if os(iOS)
        guard let controller = pipController else {
            fileLog.log("📺 PiP: no controller — cannot start")
            return
        }
        if controller.isPictureInPicturePossible && !controller.isPictureInPictureActive {
            controller.startPictureInPicture()
            isPiPActive = true
            fileLog.log("📺 PiP: started")
        } else {
            fileLog.log("📺 PiP: cannot start — possible=\(controller.isPictureInPicturePossible) active=\(controller.isPictureInPictureActive)")
        }
        #endif
    }

    /// Stop PiP (called when app returns to foreground)
    func stopPiP() {
        #if os(iOS)
        if let controller = pipController, controller.isPictureInPictureActive {
            controller.stopPictureInPicture()
        }
        isPiPActive = false
        #endif
    }

    /// Release the PiP controller without leaving AVKit pointing at freed memory.
    ///
    /// 🔴 FIX (2026-08-20) — THIS IS THE CRASH THAT ENDED THE 30-MINUTE CALL.
    ///
    /// Confirmed from the device's own report, `OSHI-2026-08-20-202149.ips`
    /// (build 93, iOS 26.6): `EXC_BAD_ACCESS` / `SIGSEGV`,
    /// `KERN_INVALID_ADDRESS`, main thread, in `objc_retain`:
    ///
    ///     objc_retain
    ///     -[AVSampleBufferDisplayLayer avkit_sampleBufferDisplayLayerPlayerController]
    ///     -[AVSampleBufferDisplayLayer avkit_videoRectInWindow]
    ///     -[AVPictureInPicturePlatformAdapter _updateVideoRectInScreenIfNeeded]
    ///     -[AVPictureInPicturePlatformAdapter _isFullScreen]
    ///     -[AVPictureInPicturePlatformAdapter _updatePictureInPictureShouldStartWhenEnteringBackground]
    ///     -[AVPictureInPicturePlatformAdapter setStatus:]
    ///     -[AVObservationController startObserving:...]_block_invoke_2
    ///     _dispatch_call_block_and_release        ← already queued on the main queue
    ///
    /// Read it bottom-up: AVKit had ALREADY enqueued an observation callback on
    /// the main queue when `stopVideo()` ran. `stopVideo()` released the
    /// controller and the playback delegate on the spot, and SwiftUI removed
    /// `VideoCallView` a few milliseconds later — taking the display layer's
    /// window with it. The queued block then ran, walked layer → player
    /// controller, and retained an object that no longer existed.
    ///
    /// Three things were wrong, and all three are fixed here:
    ///
    ///  1. `canStartPictureInPictureAutomaticallyFromInline` was left ON during
    ///     teardown. That flag is the entire reason
    ///     `_updatePictureInPictureShouldStartWhenEnteringBackground` — the frame
    ///     that crashed — was observing anything. Turn the observation off before
    ///     dismantling what it observes.
    ///  2. The controller's `delegate` was never cleared, so AVKit could still
    ///     call into a delegate we were in the middle of freeing.
    ///  3. Both objects were released synchronously. Work AVKit has already
    ///     scheduled cannot be un-scheduled, so the only safe move is to outlive
    ///     it: our references go now, the objects go one second from now — after
    ///     the queued blocks and the deferred `displayLayer.flush()` at +0.4 s
    ///     have all drained.
    private func teardownPiP() {
        #if os(iOS)
        isPiPActive = false
        guard let controller = pipController else {
            pipPlaybackDelegate = nil
            return
        }
        if #available(iOS 16.0, *) {
            controller.canStartPictureInPictureAutomaticallyFromInline = false
        }
        controller.delegate = nil
        if controller.isPictureInPictureActive {
            controller.stopPictureInPicture()
        }
        let delegate = pipPlaybackDelegate
        pipController = nil
        pipPlaybackDelegate = nil
        fileLog.log("📺 PiP: controller detached — release deferred 1.0s so AVKit's queued work cannot outlive it")
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            withExtendedLifetime((controller, delegate)) { }
        }
        #endif
    }

    // MARK: - Background Pause/Resume

    /// Pause video capture when app goes to background (audio continues via VoiceCallManager)
    func pauseCapture() {
        guard let session = captureSession, session.isRunning else {
            fileLog.log("📹 pauseCapture: no running session — skip")
            return
        }
        isSendingEnabled = false
        let captureSessionRef = session
        sessionQueue.async {
            captureSessionRef.stopRunning()
        }
        fileLog.log("📹 pauseCapture: capture session stopped (camera off, audio continues)")
    }

    /// Resume video capture when app returns to foreground — recreates encoder if iOS invalidated it.
    /// Safe to call even if capture is already running (no-ops gracefully).
    func resumeCapture() {
        guard let session = captureSession else {
            fileLog.log("📹 resumeCapture: no capture session — skip")
            return
        }

        // If already running and sending, nothing to do
        if session.isRunning && isSendingEnabled && compressionSession != nil {
            fileLog.log("📹 resumeCapture: already running — skip")
            return
        }

        isStopped = false

        // Reset display layer if it failed (can happen after repeated PiP cycles)
        if displayLayer.status == .failed {
            fileLog.log("📹 resumeCapture: displayLayer was in failed state — flushing")
            displayLayer.flush()
        }

        // Recreate encoder BEFORE restarting capture (so frames don't arrive before encoder is ready)
        Task {
            if compressionSession == nil {
                fileLog.log("📹 resumeCapture: encoder was invalidated — recreating")
                await setupEncoder()
            }
            // Now safe to start receiving frames
            isSendingEnabled = true

            // Restart capture session only if stopped
            if !session.isRunning {
                fileLog.log("📹 resumeCapture: restarting capture session")
                let captureSessionRef = session
                sessionQueue.async {
                    captureSessionRef.startRunning()
                }
            }
            // Force keyframe so encoder starts with clean IDR
            forceKeyframe()
            // Redémarrer la session ne garantit pas qu'une image en sorte —
            // c'est précisément ce qui a échoué en silence chez Natalia.
            armLocalCaptureWatchdog()
            fileLog.log("📹 resumeCapture: done, encoder=\(compressionSession != nil) sending=\(isSendingEnabled) running=\(session.isRunning)")
        }
    }
}

// MARK: - PiP Playback Delegate

class PiPPlaybackDelegate: NSObject, AVPictureInPictureSampleBufferPlaybackDelegate, AVPictureInPictureControllerDelegate {

    // 📺 Live call PiP — always playing, never paused, no seek/skip controls.
    // WhatsApp-style: just shows the remote video stream in a small floating window.

    func pictureInPictureController(_ controller: AVPictureInPictureController,
                                     setPlaying playing: Bool) {
        // No-op for live call — always playing
    }

    func pictureInPictureControllerTimeRangeForPlayback(_ controller: AVPictureInPictureController)
        -> CMTimeRange {
        // Return a short live window — this tells iOS this is a live stream, not seekable media.
        // Using a 1-second range starting from "now" prevents skip/seek controls from appearing.
        let now = CMClockGetTime(CMClockGetHostTimeClock())
        return CMTimeRange(start: now, duration: CMTime(seconds: 1, preferredTimescale: 600))
    }

    func pictureInPictureControllerIsPlaybackPaused(_ controller: AVPictureInPictureController) -> Bool {
        return false  // Always playing (live call)
    }

    func pictureInPictureController(_ controller: AVPictureInPictureController,
                                     skipByInterval interval: CMTime) async {
        // No-op — live stream, no skip
    }

    func pictureInPictureController(_ controller: AVPictureInPictureController,
                                     didTransitionToRenderSize newRenderSize: CMVideoDimensions) {}

    func pictureInPictureControllerWillStartPictureInPicture(_ controller: AVPictureInPictureController) {
        CallFileLogger.shared.log("📺 PiP: will start")
    }

    func pictureInPictureControllerDidStartPictureInPicture(_ controller: AVPictureInPictureController) {
        CallFileLogger.shared.log("📺 PiP: did start")
    }

    func pictureInPictureControllerWillStopPictureInPicture(_ controller: AVPictureInPictureController) {
        CallFileLogger.shared.log("📺 PiP: will stop")
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        CallFileLogger.shared.log("📺 PiP: did stop")
    }
}

// MARK: - Remote Video Layer View (UIViewRepresentable)
// Wraps AVSampleBufferDisplayLayer in a UIView for the SwiftUI view hierarchy.
// This is REQUIRED for PiP — the layer must be in the window's layer tree.

private class DisplayLayerHostView: UIView {
    let displayLayer: AVSampleBufferDisplayLayer

    init(displayLayer: AVSampleBufferDisplayLayer) {
        self.displayLayer = displayLayer
        super.init(frame: .zero)
        backgroundColor = .clear
        displayLayer.videoGravity = .resizeAspect
        displayLayer.backgroundColor = UIColor.black.cgColor
        layer.addSublayer(displayLayer)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        displayLayer.frame = bounds
        CATransaction.commit()
    }
}

struct RemoteVideoLayerView: UIViewRepresentable {
    let displayLayer: AVSampleBufferDisplayLayer
    /// When set (and `oshi.pip.videoCall` is on), the on-screen video is the
    /// video-call PiP content VC's view, hosted inside a source container AVKit
    /// animates the PiP from. Falls back to hosting `displayLayer` directly.
    weak var videoManager: VideoCallManager?

    func makeUIView(context: Context) -> UIView {
        #if os(iOS)
        if let vm = videoManager, vm.useVideoCallPiP {
            // Source container: AVKit shows the content VC's view here inline and
            // lifts it into the PiP window on start. We embed the VC's view pinned
            // to the container; the displayLayer lives inside that VC's view.
            let container = UIView()
            container.backgroundColor = .black
            let vc = vm.ensurePiPVideoCallController()
            if let sub = vc.view {
                sub.translatesAutoresizingMaskIntoConstraints = false
                if sub.superview !== container {
                    sub.removeFromSuperview()
                    container.addSubview(sub)
                    NSLayoutConstraint.activate([
                        sub.leadingAnchor.constraint(equalTo: container.leadingAnchor),
                        sub.trailingAnchor.constraint(equalTo: container.trailingAnchor),
                        sub.topAnchor.constraint(equalTo: container.topAnchor),
                        sub.bottomAnchor.constraint(equalTo: container.bottomAnchor)
                    ])
                }
            }
            vm.pipSourceView = container
            // Now that the source view exists, (re)build the PiP controller so it
            // picks the video-call path (setupPiP upgrades from any fallback). Next
            // runloop so the container is in the window hierarchy first.
            DispatchQueue.main.async { vm.setupPiP() }
            return container
        }
        #endif
        return DisplayLayerHostView(displayLayer: displayLayer)
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}
