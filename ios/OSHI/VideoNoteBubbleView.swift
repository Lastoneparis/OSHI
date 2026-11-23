//
//  VideoNoteBubbleView.swift
//  OSHI
//
//  __VIDEO_NOTE_2026_09_24__ The round bubble a video note is drawn as, in 1:1
//  and in groups (docs/VIDEO_NOTE_SPEC.md §4). First frame in a circle with the
//  OSHI ring around it; while on screen it loops SILENTLY; tap restarts it WITH
//  sound, tap again pauses, a brand progress ring runs around the circle, the
//  capsule shows the time left (29/09, same on Android). The
//  plaintext copy AVPlayer needs lives in `MediaPlaintextTemp` only while the
//  note is loaded in a player and is deleted when it stops or scrolls away.
//

import SwiftUI
import AVFoundation
import UIKit

/// One inline player per bubble.
///
/// __VIDEO_NOTE_AUTOLOOP_2026_09_29__ Two modes, like the messengers people know:
/// - `.preview`: while the bubble is on screen the note loops SILENTLY. It plays a
///   video-only composition, so it has no audio at all and can never interrupt the
///   user's music or take the audio session.
/// - `.sound`: a tap restarts it from the beginning WITH sound (the full file),
///   with the progress ring. At the end, or when another note starts talking, it
///   falls back to the silent loop.
/// Only one note talks at a time; any number may loop silently.
final class VideoNotePlayback: ObservableObject {
    enum Mode { case idle, preview, sound }

    @Published private(set) var player: AVPlayer?
    @Published private(set) var mode: Mode = .idle
    /// True only while the note plays WITH sound (a paused sound note is false).
    @Published private(set) var isPlaying = false
    @Published private(set) var progress: Double = 0
    @Published private(set) var elapsedMs: Int = 0

    private var tempURL: URL?
    private var asset: AVURLAsset?
    private var silentAsset: AVAsset?
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var othersObserver: NSObjectProtocol?
    private let token = UUID()
    private var setupTask: Task<Void, Never>?

    static let didStart = Notification.Name("OSHIVideoNoteDidStartPlaying")

    init() {
        othersObserver = NotificationCenter.default.addObserver(forName: Self.didStart, object: nil, queue: .main) { [weak self] note in
            guard let self, (note.object as? UUID) != self.token else { return }
            // Someone else is talking now: go quiet, keep looping.
            if self.mode == .sound { self.backToPreviewOrStop() }
        }
    }

    deinit {
        if let othersObserver { NotificationCenter.default.removeObserver(othersObserver) }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        if let timeObserver, let player { player.removeTimeObserver(timeObserver) }
        player?.pause()
        if let tempURL { MediaPlaintextTemp.remove(tempURL) }
    }

    /// Silent looping preview, started when the bubble appears.
    var autoplayAllowed = true

    func startPreview(data: Data) {
        guard mode == .idle, autoplayAllowed, setupTask == nil else { return }
        setupTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { self.setupTask = nil }
            guard self.prepareAsset(data: data), let silent = await self.loadSilentAsset() else { return }
            guard !Task.isCancelled, self.mode == .idle else { return }
            self.install(item: AVPlayerItem(asset: silent), muted: true)
            self.mode = .preview
            self.player?.play()
        }
    }

    /// Tap: from silence → from the start with sound; with sound → pause/resume.
    func tap(data: Data) {
        switch mode {
        case .idle, .preview:
            playWithSound(data: data)
        case .sound:
            if isPlaying {
                player?.pause(); isPlaying = false
            } else {
                VideoNotePlaybackAudio.activate()
                NotificationCenter.default.post(name: Self.didStart, object: token)
                player?.play(); isPlaying = true
            }
        }
    }

    private func playWithSound(data: Data) {
        setupTask?.cancel(); setupTask = nil
        guard prepareAsset(data: data), let asset else { return }
        install(item: AVPlayerItem(asset: asset), muted: false)
        VideoNotePlaybackAudio.activate()
        NotificationCenter.default.post(name: Self.didStart, object: token)
        mode = .sound
        progress = 0; elapsedMs = 0
        player?.play()
        isPlaying = true
    }

    /// Scrolled away / bubble gone: nothing keeps playing, no plaintext stays.
    func stop() {
        setupTask?.cancel(); setupTask = nil
        mode = .idle
        isPlaying = false
        progress = 0
        elapsedMs = 0
        teardownPlayer()
    }

    // MARK: - Internals

    private func prepareAsset(data: Data) -> Bool {
        if asset != nil { return true }
        guard let url = try? MediaPlaintextTemp.write(data, fileName: "videonote.mp4") else { return false }  // __PLAINTEXT_LEFTOVERS_2026_09_24__
        tempURL = url
        asset = AVURLAsset(url: url)
        return true
    }

    /// The same clip without its audio track.
    private func loadSilentAsset() async -> AVAsset? {
        if let silentAsset { return silentAsset }
        guard let asset,
              let video = try? await asset.loadTracks(withMediaType: .video).first,
              let duration = try? await asset.load(.duration) else { return nil }
        let composition = AVMutableComposition()
        guard let track = composition.addMutableTrack(withMediaType: .video,
                                                      preferredTrackID: kCMPersistentTrackID_Invalid),
              (try? track.insertTimeRange(CMTimeRange(start: .zero, duration: duration), of: video, at: .zero)) != nil
        else { return nil }
        track.preferredTransform = (try? await video.load(.preferredTransform)) ?? .identity
        silentAsset = composition
        return composition
    }

    private func install(item: AVPlayerItem, muted: Bool) {
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        if let player {
            player.replaceCurrentItem(with: item)
        } else {
            let player = AVPlayer(playerItem: item)
            player.actionAtItemEnd = .none
            player.preventsDisplaySleepDuringVideoPlayback = false
            timeObserver = player.addPeriodicTimeObserver(
                forInterval: CMTime(value: 1, timescale: 30), queue: .main) { [weak self] time in
                    guard let self, self.mode == .sound, let item = self.player?.currentItem else { return }
                    let total = item.duration.seconds
                    let now = time.seconds
                    guard total.isFinite, total > 0, now.isFinite else { return }
                    self.progress = min(max(now / total, 0), 1)
                    self.elapsedMs = Int(now * 1000)
                }
            self.player = player
        }
        player?.isMuted = muted
        player?.preventsDisplaySleepDuringVideoPlayback = !muted
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                self?.reachedEnd()
            }
    }

    private func reachedEnd() {
        switch mode {
        case .preview:
            player?.seek(to: .zero)
            player?.play()
        case .sound:
            backToPreviewOrStop()
        case .idle:
            break
        }
    }

    /// After a note spoke: loop silently again (or rest on the first frame when
    /// autoplay is off — Reduce Motion / Low Power).
    private func backToPreviewOrStop() {
        isPlaying = false
        progress = 0
        elapsedMs = 0
        guard autoplayAllowed, let silentAsset else {
            mode = .idle
            teardownPlayer()
            return
        }
        install(item: AVPlayerItem(asset: silentAsset), muted: true)
        mode = .preview
        player?.play()
    }

    private func teardownPlayer() {
        player?.pause()
        if let timeObserver, let player { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = nil
        player = nil
        asset = nil
        silentAsset = nil
        if let tempURL { MediaPlaintextTemp.remove(tempURL) }
        tempURL = nil
    }
}

struct VideoNoteBubbleView: View {
    let messageId: String
    /// nil while the bytes are still loading / decrypting.
    let videoData: Data?
    let durationMs: Int?
    let isOutgoing: Bool
    /// Optional already-decoded first frame (the group bubble has one).
    var poster: UIImage? = nil

    @StateObject private var playback = VideoNotePlayback()
    @State private var loadedPoster: UIImage?
    @State private var measuredMs: Int?
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var diameter: CGFloat { sizeClass == .regular ? 240 : 220 }
    private var totalMs: Int { durationMs ?? measuredMs ?? 0 }

    private var capsuleSymbol: String {
        switch playback.mode {
        case .sound: return playback.isPlaying ? "speaker.wave.2.fill" : "pause.fill"
        case .preview: return "speaker.slash.fill"
        case .idle: return "video.fill"
        }
    }

    var body: some View {
        lifecycle(tappableCircle)
    }

    private var tappableCircle: some View {
        circleContent
            .frame(width: diameter, height: diameter)
            .contentShape(Circle())
            .shadow(color: .black.opacity(0.12), radius: 6, y: 3)
            .animation(reduceMotion ? .easeInOut(duration: 0.15) : .spring(response: 0.3, dampingFraction: 0.8),
                       value: playback.isPlaying)
            .onTapGesture {
                guard let videoData else { return }
                playback.tap(data: videoData)
            }
    }

    private var remainingText: String {
        VideoNote.formatted(ms: playback.mode == .sound ? max(0, totalMs - playback.elapsedMs) : totalMs)
    }

    private var durationCapsule: some View {
        HStack(spacing: 4) {
            Image(systemName: capsuleSymbol)
                .font(.system(size: 9, weight: .bold))
                .contentTransition(.symbolEffect(.replace))
            Text(remainingText)
                .font(.caption2.monospacedDigit().weight(.semibold))
        }
        .foregroundColor(.white)
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(Capsule().fill(Color.black.opacity(0.5)))
    }

    private var rim: some View {
        let style: AnyShapeStyle = isOutgoing ? AnyShapeStyle(Theme.brandGradient) : AnyShapeStyle(Color(.systemGray3))
        return Circle()
            .stroke(style, lineWidth: 3)
            .opacity(playback.mode == .sound ? 0.35 : 1)
    }

    private var showsPlayButton: Bool {
        !playback.isPlaying && playback.mode != .preview && videoData != nil
    }

    private var circleContent: some View {
        ZStack {
            Circle().fill(Color(.systemGray5))

            if let image = poster ?? loadedPoster {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: diameter, height: diameter)
                    .clipShape(Circle())
            }

            if let player = playback.player {
                VideoNotePlayerLayer(player: player)
                    .frame(width: diameter, height: diameter)
                    .clipShape(Circle())
                    .transition(.opacity)
            }

            if videoData == nil {
                ProgressView()
            }

            // The OSHI "O": a quiet rim at rest, the brand gradient filling it
            // while the note plays.
            rim
            if playback.mode == .sound {
                VideoNoteRing(progress: playback.progress, lineWidth: 5, trackOpacity: 0)
                    .padding(2.5)
            }

            // Big play button only when nothing moves (autoplay off, or a
            // paused note). A silent loop says "tap for sound" with the
            // muted glyph in the capsule instead.
            if showsPlayButton {
                Image(systemName: "play.fill")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundColor(.white)
                    .frame(width: 52, height: 52)
                    .background(Circle().fill(Color.black.opacity(0.45)))
                    .transition(reduceMotion ? .opacity : .scale.combined(with: .opacity))
            }

            VStack {
                Spacer()
                durationCapsule
                    .padding(.bottom, 12)
            }
        }
    }

    private func lifecycle<V: View>(_ content: V) -> some View {
        content
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("videonote.a11y.bubble".localized(with: VideoNote.formatted(ms: totalMs))))
        .accessibilityHint(Text(playback.isPlaying ? "videonote.a11y.pause".localized : "videonote.a11y.play".localized))
        .accessibilityAddTraits(.isButton)
        .accessibilityAction {
            guard let videoData else { return }
            playback.tap(data: videoData)
        }
        .task(id: videoData?.count ?? 0) {
            guard let videoData, poster == nil, loadedPoster == nil else { return }
            if let jpeg = await MediaManager().videoPosterFrame(forMessageId: messageId, videoData: videoData),
               let image = UIImage(data: jpeg) {
                loadedPoster = image
            }
            if durationMs == nil { measuredMs = await Self.measureDuration(videoData) }
        }
        .task(id: videoData?.count ?? 0) {
            // On screen (LazyVStack creates the bubble when it scrolls in) and
            // loaded: loop silently. Off for Reduce Motion and Low Power Mode.
            playback.autoplayAllowed = !reduceMotion && !ProcessInfo.processInfo.isLowPowerModeEnabled
            if let videoData { playback.startPreview(data: videoData) }
        }
        .onDisappear { playback.stop() }
    }

    /// Duration from the file itself, for a note whose wire duration was missing.
    private static func measureDuration(_ data: Data) async -> Int? {
        guard let url = try? MediaPlaintextTemp.write(data, fileName: "videonote.mp4") else { return nil }
        defer { MediaPlaintextTemp.remove(url) }
        guard let d = try? await AVURLAsset(url: url).load(.duration), d.seconds.isFinite else { return nil }
        return VideoNoteRecordingClock.durationMs(forRecorded: d.seconds)
    }
}
