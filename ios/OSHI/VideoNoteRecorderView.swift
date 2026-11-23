//
//  VideoNoteRecorderView.swift
//  OSHI
//
//  __VIDEO_NOTE_2026_09_24__ The round video recorder: a compact circle over
//  the chat, not a full-screen camera. Front camera by default, a brand ring
//  that fills over 15 s, tap to record / tap to stop (or hold, slide left to
//  cancel), a hard stop at 15.0 s, then a looping preview with send / retake /
//  discard. The clip is re-encoded to the square MP4 of docs/VIDEO_NOTE_SPEC.md
//  §3 and every plaintext file (raw capture, encoded clip) lives in
//  `MediaPlaintextTemp` and is deleted as soon as it has been used.
//

import SwiftUI
@preconcurrency import AVFoundation
import UIKit

// MARK: - Capture controller

final class VideoNoteCaptureController: NSObject, ObservableObject {
    enum Phase: Equatable {
        case starting, ready, recording, processing, preview, denied, unavailable, failed
    }

    struct Recorded: Equatable {
        let url: URL
        let durationMs: Int
        let byteCount: Int
    }

    @Published private(set) var phase: Phase = .starting
    @Published private(set) var elapsed: TimeInterval = 0
    @Published private(set) var recorded: Recorded?
    @Published private(set) var usingFrontCamera = true
    @Published private(set) var canFlip = false
    /// Set briefly when a clip was too short to keep ("hold longer").
    @Published var tooShortFlash = false

    let session = AVCaptureSession()
    private let movieOutput = AVCaptureMovieFileOutput()
    private let sessionQueue = DispatchQueue(label: "oshi.videonote.session")
    private var videoInput: AVCaptureDeviceInput?
    private var rawURL: URL?
    private var tick: Timer?
    private var startedAt: Date?
    private var discardOnFinish = false
    private var configured = false
    private var rotationCoordinator: AnyObject?
    /// Set by `teardown`: an encode still running when the overlay closes must
    /// delete its output instead of publishing it.
    private var tornDown = false

    #if DEBUG
    /// `-OSHIVideoNoteRecorderDemo`: a still frame and a frozen ring, so the
    /// recorder can be captured on a simulator (which has no camera).
    @Published private(set) var demoFrame: UIImage?
    #endif

    /// Any camera at all? False on a Mac without one and on the simulator.
    static var hasCamera: Bool {
        AVCaptureDevice.default(for: .video) != nil
    }

    /// Evaluated once: the composer asks on every render.
    static let cameraPresent: Bool = hasCamera

    // MARK: Lifecycle

    func start() {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-OSHIVideoNoteRecorderDemo") {
            demoFrame = UIImage(named: "demo_alice")
                ?? Bundle.main.url(forResource: "demo_alice", withExtension: "jpg")
                    .flatMap { try? Data(contentsOf: $0) }.flatMap(UIImage.init(data:))
            elapsed = 9.2
            phase = .recording
            return
        }
        #endif
        guard Self.hasCamera else { phase = .unavailable; return }
        Task { @MainActor in
            let video = await Self.authorize(.video)
            guard video else { phase = .denied; return }
            let audio = await Self.authorize(.audio)
            sessionQueue.async { [weak self] in
                guard let self else { return }
                if !self.configured { self.configure(withAudio: audio) }
                if !self.session.isRunning { self.session.startRunning() }
                let ok = self.videoInput != nil
                DispatchQueue.main.async {
                    self.phase = ok ? .ready : .failed
                    self.canFlip = AVCaptureDevice.DiscoverySession(
                        deviceTypes: [.builtInWideAngleCamera], mediaType: .video, position: .unspecified
                    ).devices.count > 1
                }
            }
        }
    }

    /// Stops the camera and deletes every plaintext file still around.
    func teardown() {
        tornDown = true
        tick?.invalidate(); tick = nil
        if movieOutput.isRecording {
            discardOnFinish = true
            movieOutput.stopRecording()
        }
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
        removeFiles()
    }

    private static func authorize(_ type: AVMediaType) async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: type) {
        case .authorized: return true
        case .notDetermined: return await AVCaptureDevice.requestAccess(for: type)
        default: return false
        }
    }

    private func configure(withAudio audio: Bool) {
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        // 640×480 is the cheapest preset that still covers a 480×480 centre crop.
        if session.canSetSessionPreset(.vga640x480) {
            session.sessionPreset = .vga640x480
        } else if session.canSetSessionPreset(.high) {
            session.sessionPreset = .high
        }
        let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front)
            ?? AVCaptureDevice.default(for: .video)
        if let device, let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) {
            session.addInput(input)
            videoInput = input
            let front = device.position == .front
            DispatchQueue.main.async { self.usingFrontCamera = front }
        }
        if audio, let mic = AVCaptureDevice.default(for: .audio),
           let input = try? AVCaptureDeviceInput(device: mic), session.canAddInput(input) {
            session.addInput(input)
        }
        if session.canAddOutput(movieOutput) {
            session.addOutput(movieOutput)
            // The 15.0 s hard limit, enforced by the capture pipeline itself.
            movieOutput.maxRecordedDuration = CMTime(seconds: VideoNote.maxDuration, preferredTimescale: 600)
        }
        configured = true
    }

    /// The device the preview layer should follow for rotation.
    var currentDevice: AVCaptureDevice? { videoInput?.device }

    // MARK: Recording

    func toggleRecording() {
        switch phase {
        case .ready: startRecording()
        case .recording: stopRecording()
        default: break
        }
    }

    func startRecording() {
        guard phase == .ready, !movieOutput.isRecording else { return }
        let url = MediaPlaintextTemp.scratchURL(ext: "mov")  // __PLAINTEXT_LEFTOVERS_2026_09_24__
        rawURL = url
        discardOnFinish = false
        elapsed = 0
        phase = .recording
        startedAt = Date()
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        let front = usingFrontCamera
        sessionQueue.async { [weak self] in
            guard let self else { return }
            if let connection = self.movieOutput.connection(with: .video) {
                self.applyCaptureRotation(to: connection)
                // Recorded as seen in the (mirrored) selfie preview, like Telegram.
                if connection.isVideoMirroringSupported {
                    connection.automaticallyAdjustsVideoMirroring = false
                    connection.isVideoMirrored = front
                }
            }
            self.movieOutput.startRecording(to: url, recordingDelegate: self)
        }
        tick?.invalidate()
        tick = Timer.scheduledTimer(withTimeInterval: 1.0 / 30.0, repeats: true) { [weak self] _ in
            guard let self, let startedAt = self.startedAt else { return }
            let t = Date().timeIntervalSince(startedAt)
            self.elapsed = min(t, VideoNote.maxDuration)
            if VideoNoteRecordingClock.shouldAutoStop(elapsed: t) {
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                self.stopRecording()
            }
        }
    }

    func stopRecording() {
        tick?.invalidate(); tick = nil
        guard phase == .recording else { return }
        phase = .processing
        sessionQueue.async { [movieOutput] in
            if movieOutput.isRecording { movieOutput.stopRecording() }
        }
    }

    /// Hold-to-record released over the cancel zone, or the close button mid-take.
    func cancelRecording() {
        tick?.invalidate(); tick = nil
        guard phase == .recording else { return }
        discardOnFinish = true
        phase = .ready
        elapsed = 0
        sessionQueue.async { [movieOutput] in
            if movieOutput.isRecording { movieOutput.stopRecording() }
        }
    }

    func retake() {
        tornDown = false
        removeFiles()
        recorded = nil
        elapsed = 0
        phase = .starting
        start()
    }

    func flipCamera() {
        guard phase == .ready else { return }
        let wantFront = !usingFrontCamera
        sessionQueue.async { [weak self] in
            guard let self,
                  let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video,
                                                       position: wantFront ? .front : .back),
                  let input = try? AVCaptureDeviceInput(device: device) else { return }
            self.session.beginConfiguration()
            if let old = self.videoInput { self.session.removeInput(old) }
            if self.session.canAddInput(input) {
                self.session.addInput(input)
                self.videoInput = input
            } else if let old = self.videoInput {
                self.session.addInput(old)
            }
            self.session.commitConfiguration()
            let front = self.videoInput?.device.position == .front
            DispatchQueue.main.async { self.usingFrontCamera = front }
        }
    }

    /// Reads the encoded clip into memory and deletes it: after this call no
    /// plaintext copy of the note remains on disk (the send pipeline encrypts
    /// the bytes before anything is written again).
    func takeForSend() -> (Data, VideoNoteMetadata)? {
        guard let recorded, let data = try? Data(contentsOf: recorded.url) else { return nil }
        let meta = VideoNoteMetadata(durationMs: recorded.durationMs)
        removeFiles()
        self.recorded = nil
        return (data, meta)
    }

    private func removeFiles() {
        if let rawURL { MediaPlaintextTemp.remove(rawURL); self.rawURL = nil }
        if let url = recorded?.url { MediaPlaintextTemp.remove(url) }
    }

    private func applyCaptureRotation(to connection: AVCaptureConnection) {
        #if targetEnvironment(macCatalyst)
        return
        #else
        if let device = videoInput?.device {
            let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: nil)
            rotationCoordinator = coordinator
            let angle = coordinator.videoRotationAngleForHorizonLevelCapture
            if connection.isVideoRotationAngleSupported(angle) { connection.videoRotationAngle = angle }
        }
        #endif
    }
}

extension VideoNoteCaptureController: AVCaptureFileOutputRecordingDelegate {
    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL,
                    from connections: [AVCaptureConnection], error: Error?) {
        // Reaching `maxRecordedDuration` reports an "error" whose userInfo says the
        // file is complete — that is the 15 s auto-stop, a success.
        let finishedOK = error == nil
            || ((error as NSError?)?.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool == true)
        DispatchQueue.main.async {
            self.tick?.invalidate(); self.tick = nil
            if self.discardOnFinish || !finishedOK {
                MediaPlaintextTemp.remove(outputFileURL)
                self.rawURL = nil
                if self.phase != .preview { self.phase = finishedOK ? .ready : .failed }
                self.discardOnFinish = false
                return
            }
            self.phase = .processing
            let mirror = false  // already mirrored at capture (see startRecording)
            Task.detached(priority: .userInitiated) {
                let out = MediaPlaintextTemp.scratchURL(ext: "mp4")  // __PLAINTEXT_LEFTOVERS_2026_09_24__
                let result = try? await VideoNoteEncoder.encode(input: outputFileURL, output: out, mirror: mirror)
                MediaPlaintextTemp.remove(outputFileURL)
                await MainActor.run {
                    self.rawURL = nil
                    if self.tornDown {  // closed while encoding: leave nothing behind
                        MediaPlaintextTemp.remove(out)
                        return
                    }
                    guard let result,
                          VideoNoteRecordingClock.acceptsForSend(duration: Double(result.durationMs) / 1000.0) else {
                        MediaPlaintextTemp.remove(out)
                        self.tooShortFlash = result != nil
                        self.phase = result == nil ? .failed : .ready
                        self.elapsed = 0
                        return
                    }
                    self.recorded = Recorded(url: result.url, durationMs: result.durationMs, byteCount: result.byteCount)
                    self.phase = .preview
                    // Free the camera (and its privacy indicator) while previewing.
                    self.sessionQueue.async { [session = self.session] in
                        if session.isRunning { session.stopRunning() }
                    }
                }
            }
        }
    }
}

// MARK: - Camera preview (circle-clipped by the caller)

struct VideoNoteCameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    let device: AVCaptureDevice?

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
        var coordinator: AnyObject?
        var observation: NSKeyValueObservation?
    }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        view.backgroundColor = .black
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {
        #if !targetEnvironment(macCatalyst)
        guard let device, (view.coordinator as? AVCaptureDevice.RotationCoordinator)?.device != device else { return }
        let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: view.previewLayer)
        view.coordinator = coordinator
        let apply = { [weak view] (angle: CGFloat) in
            guard let connection = view?.previewLayer.connection,
                  connection.isVideoRotationAngleSupported(angle) else { return }
            connection.videoRotationAngle = angle
        }
        apply(coordinator.videoRotationAngleForHorizonLevelPreview)
        view.observation = coordinator.observe(\.videoRotationAngleForHorizonLevelPreview, options: [.new]) { c, _ in
            DispatchQueue.main.async { apply(c.videoRotationAngleForHorizonLevelPreview) }
        }
        #endif
    }
}

// MARK: - Looping player layer

struct VideoNotePlayerLayer: UIViewRepresentable {
    let player: AVPlayer

    final class PlayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context: Context) -> PlayerView {
        let view = PlayerView()
        view.playerLayer.videoGravity = .resizeAspectFill
        view.playerLayer.player = player
        view.backgroundColor = .clear
        return view
    }

    func updateUIView(_ view: PlayerView, context: Context) {
        if view.playerLayer.player !== player { view.playerLayer.player = player }
    }
}

// MARK: - Brand ring

/// The OSHI "O": a thick ring in the brand violet → pink gradient.
struct VideoNoteRing: View {
    var progress: Double
    var lineWidth: CGFloat = 6
    var trackOpacity: Double = 0.22

    static let gradient = AngularGradient(
        colors: [Theme.brand, Theme.brandSecondary, Color(red: 0.20, green: 0.40, blue: 0.95), Theme.brand],
        center: .center)

    var body: some View {
        ZStack {
            Circle().stroke(Color.white.opacity(trackOpacity), lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: max(0, min(progress, 1)))
                .stroke(Self.gradient, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
    }
}

// MARK: - Overlay

struct VideoNoteRecorderOverlay: View {
    let onSend: (Data, VideoNoteMetadata) -> Void
    let onClose: () -> Void

    @StateObject private var capture = VideoNoteCaptureController()
    @State private var previewPlayer: AVQueuePlayer?
    @State private var looper: AVPlayerLooper?
    @State private var appeared = false
    @State private var holdMode = false
    @State private var pressBegan: Date?
    @State private var holdWork: DispatchWorkItem?
    @State private var dragX: CGFloat = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.openURL) private var openURL

    private let cancelThreshold: CGFloat = -90

    var body: some View {
        GeometryReader { geo in
            let diameter = min(320, min(geo.size.width, geo.size.height) * 0.74)
            ZStack {
                Rectangle()
                    .fill(.ultraThinMaterial)
                    .overlay(Color.black.opacity(0.45))
                    .ignoresSafeArea()
                    .onTapGesture { if capture.phase != .recording { close() } }
                    .accessibilityHidden(true)

                VStack(spacing: 22) {
                    Spacer(minLength: 0)
                    header
                    circle(diameter: diameter)
                        .scaleEffect(appeared || reduceMotion ? 1 : 0.55)
                        .opacity(appeared ? 1 : 0)
                    controls
                        .opacity(appeared ? 1 : 0)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 16)
                .frame(maxWidth: .infinity)
            }
        }
        .onAppear {
            capture.start()
            withAnimation(reduceMotion ? .easeOut(duration: 0.2) : .spring(response: 0.42, dampingFraction: 0.72)) {
                appeared = true
            }
        }
        .onDisappear {
            stopPreviewPlayer()
            capture.teardown()
        }
        .onChange(of: capture.phase) { _, phase in
            if phase == .preview { startPreviewPlayer() } else if phase != .processing { stopPreviewPlayer() }
            if phase == .recording || phase == .ready { UIAccessibility.post(notification: .announcement, argument: hintText) }
        }
        .onChange(of: capture.tooShortFlash) { _, flash in
            guard flash else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { capture.tooShortFlash = false }
        }
        .statusBarHidden(false)
    }

    // MARK: Header

    private var hintText: String {
        switch capture.phase {
        case .starting, .processing: return "videonote.processing".localized
        case .ready:
            return capture.tooShortFlash ? "videonote.too_short".localized : "videonote.hint.tap".localized
        case .recording:
            if holdMode { return dragX < cancelThreshold ? "videonote.release_cancel".localized : "videonote.hint.slide".localized }
            return "videonote.hint.recording".localized
        case .preview: return "videonote.hint.preview".localized
        case .denied: return "videonote.camera_denied".localized
        case .unavailable: return "videonote.no_camera".localized
        case .failed: return "videonote.failed".localized
        }
    }

    private var header: some View {
        VStack(spacing: 6) {
            Text("videonote.title".localized)
                .font(.headline)
                .foregroundColor(.white)
            Text(hintText)
                .font(.subheadline)
                .foregroundColor(.white.opacity(0.85))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .animation(.easeInOut(duration: 0.2), value: hintText)
        }
        .accessibilityElement(children: .combine)
    }

    // MARK: Circle

    @ViewBuilder
    private func circle(diameter: CGFloat) -> some View {
        ZStack {
            Circle().fill(Theme.brandGradient)
            circleContent
                .frame(width: diameter, height: diameter)
                .clipShape(Circle())
            if capture.phase == .processing || capture.phase == .starting {
                Circle().fill(Color.black.opacity(0.35))
                ProgressView().tint(.white).controlSize(.large)
            }
            VideoNoteRing(progress: ringProgress, lineWidth: 7)
                .padding(-10)
                .animation(capture.phase == .recording ? .linear(duration: 1.0 / 30.0) : .easeOut(duration: 0.25),
                           value: ringProgress)
            VStack {
                Spacer()
                timeCapsule
                    .padding(.bottom, 14)
            }
        }
        .frame(width: diameter, height: diameter)
        .shadow(color: Theme.brand.opacity(0.45), radius: 24, y: 8)
        .contentShape(Circle())
        .onTapGesture {
            guard capture.phase == .preview, let p = previewPlayer else { return }
            if p.timeControlStatus == .playing { p.pause() } else { p.play() }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("videonote.title".localized))
        .accessibilityValue(Text(VideoNote.formatted(ms: Int(capture.elapsed * 1000))))
    }

    @ViewBuilder
    private var circleContent: some View {
        #if DEBUG
        if let frame = capture.demoFrame {
            Image(uiImage: frame).resizable().scaledToFill()
        } else {
            liveContent
        }
        #else
        liveContent
        #endif
    }

    @ViewBuilder
    private var liveContent: some View {
        switch capture.phase {
        case .preview:
            if let previewPlayer { VideoNotePlayerLayer(player: previewPlayer) }
        case .ready, .recording, .processing, .starting:
            VideoNoteCameraPreview(session: capture.session, device: capture.currentDevice)
        case .denied, .unavailable, .failed:
            VStack(spacing: 10) {
                Image(systemName: capture.phase == .denied ? "video.slash.fill" : "video.fill")
                    .font(.system(size: 44, weight: .semibold))
                    .foregroundColor(.white.opacity(0.9))
                    .accessibilityHidden(true)
            }
        }
    }

    private var ringProgress: Double {
        switch capture.phase {
        case .recording, .processing: return VideoNoteRecordingClock.progress(elapsed: capture.elapsed)
        case .preview: return 1
        default: return 0
        }
    }

    private var timeCapsule: some View {
        HStack(spacing: 6) {
            if capture.phase == .recording {
                Circle().fill(Color.red).frame(width: 8, height: 8)
            }
            let ms = capture.phase == .preview ? (capture.recorded?.durationMs ?? 0) : Int(capture.elapsed * 1000)
            Text(VideoNote.formatted(ms: ms) + " / " + VideoNote.formatted(ms: VideoNote.hardCapMs))
                .font(.caption.monospacedDigit().weight(.semibold))
                .foregroundColor(.white)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(Capsule().fill(Color.black.opacity(0.45)))
        .opacity(capture.phase == .recording || capture.phase == .preview ? 1 : 0)
        .accessibilityHidden(true)
    }

    // MARK: Controls

    @ViewBuilder
    private var controls: some View {
        switch capture.phase {
        case .preview:
            HStack(spacing: 28) {
                roundButton(system: "trash", label: "videonote.discard".localized, tint: .red) { close() }
                roundButton(system: "arrow.counterclockwise", label: "videonote.retake".localized, tint: .white) {
                    stopPreviewPlayer()
                    capture.retake()
                }
                Button(action: send) {
                    ZStack {
                        Circle()
                            .fill(LinearGradient(colors: [Theme.brand, Theme.brandSecondary],
                                                 startPoint: .topLeading, endPoint: .bottomTrailing))
                            .frame(width: 64, height: 64)
                            .neonGlow(Theme.brand, radius: 12, opacity: 0.6)
                        Image(systemName: "arrow.up")
                            .font(.system(size: 26, weight: .bold))
                            .foregroundColor(.white)
                    }
                }
                .buttonStyle(PressScaleButtonStyle())
                .accessibilityLabel(Text("general.send".localized))
            }
        case .denied:
            HStack(spacing: 20) {
                roundButton(system: "xmark", label: "common.cancel".localized, tint: .white) { close() }
                Button("common.open_settings".localized) {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
                .font(.body.weight(.semibold))
                .padding(.horizontal, 18)
                .frame(minHeight: 44)
                .background(Capsule().fill(Theme.brand))
                .foregroundColor(.white)
            }
        case .unavailable, .failed:
            roundButton(system: "xmark", label: "common.cancel".localized, tint: .white) { close() }
        default:
            HStack(spacing: 36) {
                roundButton(system: "xmark", label: "common.cancel".localized, tint: .white) {
                    capture.cancelRecording()
                    close()
                }
                recordButton
                roundButton(system: "arrow.triangle.2.circlepath.camera", label: "videonote.flip".localized, tint: .white) {
                    capture.flipCamera()
                }
                .opacity(capture.canFlip && capture.phase == .ready ? 1 : 0.35)
                .disabled(!capture.canFlip || capture.phase != .ready)
            }
        }
    }

    private var recordButton: some View {
        let recording = capture.phase == .recording
        return ZStack {
            Circle()
                .stroke(Color.white, lineWidth: 4)
                .frame(width: 76, height: 76)
            RoundedRectangle(cornerRadius: recording ? 8 : 30, style: .continuous)
                .fill(Color.red)
                .frame(width: recording ? 30 : 60, height: recording ? 30 : 60)
                .animation(reduceMotion ? nil : .spring(response: 0.3, dampingFraction: 0.7), value: recording)
        }
        .frame(width: 80, height: 80)
        .offset(x: holdMode ? min(0, dragX) : 0)
        .contentShape(Circle())
        .gesture(recordGesture)
        .disabled(capture.phase != .ready && capture.phase != .recording)
        .accessibilityElement()
        .accessibilityLabel(Text(recording ? "videonote.stop".localized : "videonote.record".localized))
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { capture.toggleRecording() }
    }

    /// Tap = start/stop. Press and hold (≥0.3 s) = record while held; release to
    /// stop, or slide left past the threshold and release to cancel.
    private var recordGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                if pressBegan == nil {
                    pressBegan = Date()
                    guard capture.phase == .ready else { return }
                    let work = DispatchWorkItem {
                        guard pressBegan != nil, capture.phase == .ready else { return }
                        holdMode = true
                        capture.startRecording()
                    }
                    holdWork = work
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3, execute: work)
                }
                if holdMode { dragX = value.translation.width }
            }
            .onEnded { value in
                holdWork?.cancel(); holdWork = nil
                defer { pressBegan = nil; holdMode = false; dragX = 0 }
                if holdMode {
                    if value.translation.width < cancelThreshold {
                        UINotificationFeedbackGenerator().notificationOccurred(.warning)
                        capture.cancelRecording()
                    } else {
                        capture.stopRecording()
                    }
                } else {
                    capture.toggleRecording()
                }
            }
    }

    private func roundButton(system: String, label: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: system)
                .font(.system(size: 20, weight: .semibold))
                .foregroundColor(tint)
                .frame(width: 52, height: 52)
                .background(Circle().fill(Color.white.opacity(0.16)))
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityLabel(Text(label))
    }

    // MARK: Actions

    private func send() {
        stopPreviewPlayer()
        guard let taken = capture.takeForSend() else { return }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        onSend(taken.0, taken.1)
        close()
    }

    private func close() {
        stopPreviewPlayer()
        capture.teardown()
        onClose()
    }

    private func startPreviewPlayer() {
        guard let url = capture.recorded?.url else { return }
        let item = AVPlayerItem(url: url)
        let player = AVQueuePlayer()
        looper = AVPlayerLooper(player: player, templateItem: item)
        previewPlayer = player
        VideoNotePlaybackAudio.activate()
        player.play()
    }

    private func stopPreviewPlayer() {
        previewPlayer?.pause()
        looper?.disableLooping()
        looper = nil
        previewPlayer = nil
    }
}

/// Audio session for playing a note WITH sound (the ring/silent switch must
/// not mute a message the user tapped to hear). Left alone during a call.
enum VideoNotePlaybackAudio {
    static func activate() {
        let session = AVAudioSession.sharedInstance()
        guard session.category != .playAndRecord else { return }
        try? session.setCategory(.playback, mode: .moviePlayback, options: [])
        try? session.setActive(true)
    }
}
