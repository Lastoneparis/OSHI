import SwiftUI
import AVFoundation

// __VOICE_MINIPLAYER_2026_10_09__
//
// A voice note used to be played by an `AudioPlayerManager` owned by the bubble
// that drew it, and every one of those bubbles did `.onDisappear { stop() }`.
// Leaving the conversation — or just switching tab — therefore cut the message
// off mid-sentence, and there was no way to get back to where it stopped.
//
// Playback now lives here, outside the view tree, for the whole app lifetime.
// The bubble only *drives* it and reads its published state; it no longer owns
// the player, so navigating away keeps the audio running and a compact player
// follows the user from view to view until the note ends or they close it.
//
// Deliberately NOT used for view-once voice notes (`ViewOnceAudioPlayer`):
// those are consumed and destroyed inside their own screen and must not be
// resumable from somewhere else.
/// Shared with the in-bubble speed controls, which read it through
/// `@AppStorage("audioPlaybackSpeed")`. Declared at file scope because `Self`
/// cannot be referenced from a stored property initializer.
private let voiceNoteSpeedKey = "audioPlaybackSpeed"

@MainActor
final class VoiceNotePlayback: NSObject, ObservableObject {

    static let shared = VoiceNotePlayback()

    /// What is loaded in the player right now, if anything.
    struct Item: Equatable {
        /// Stable id of the message that owns this note. Used both to tell the
        /// owning bubble "you are the active one" and to decide whether the
        /// mini player is redundant because that bubble is on screen.
        let id: String
        /// Shown in the mini player so the user knows what they are hearing
        /// after they have navigated away from the conversation.
        let title: String
        let duration: TimeInterval
    }

    @Published private(set) var item: Item?
    @Published private(set) var isPlaying = false
    @Published private(set) var currentTime: TimeInterval = 0
    /// Cycles 1x → 1.5x → 2x. Shared with the in-bubble control through the
    /// same `audioPlaybackSpeed` key the bubbles already used.
    @Published var speed: Double = UserDefaults.standard.double(forKey: voiceNoteSpeedKey) == 0
        ? 1.0
        : UserDefaults.standard.double(forKey: voiceNoteSpeedKey)

    /// Set by the user tapping the ✕. Keeps the note loaded (so the bubble
    /// still shows its own controls) but takes the floating player away.
    @Published private(set) var miniPlayerDismissed = false

    /// Height of whatever occupies the bottom of the CURRENT screen and must
    /// not be covered — a chat's composer, in practice. Published by
    /// `.voiceNotePlayerObstruction()` on the view that owns that bar, so the
    /// pill lifts by a MEASURED height instead of a guessed one. 0 means the
    /// only thing down there is the tab bar, which the pill already clears.
    @Published private(set) var bottomObstruction: CGFloat = 0

    /// Ids of the bubbles currently on screen. While the owner of the active
    /// note is visible it already offers play/pause and a waveform, so a
    /// floating copy on top of it would just be clutter.
    @Published private(set) var visibleOwners: Set<String> = []

    private var player: AVAudioPlayer?
    private var ticker: Timer?

    /// True when the compact player should be floating over the current screen.
    var showsMiniPlayer: Bool {
        guard let item, !miniPlayerDismissed else { return false }
        return !visibleOwners.contains(item.id)
    }

    func isActive(_ id: String) -> Bool { item?.id == id }

    /// Progress 0…1 of the active note, or 0 when nothing is loaded.
    var progress: Double {
        guard let item, item.duration > 0 else { return 0 }
        return min(1, max(0, currentTime / item.duration))
    }

    // MARK: - Transport

    func play(id: String, title: String, data: Data) {
        // Tapping play on the note that is already loaded resumes it instead of
        // restarting from zero — that is what "pause" has to mean for it to be
        // worth following the user across screens.
        if item?.id == id, player != nil {
            resume()
            return
        }

        teardown()

        VoiceMessageAudioRoute.shared.setPrefersSpeaker(true)
        VoiceMessageAudioRoute.shared.beginPlayback()

        do {
            let p = try AVAudioPlayer(data: data)
            p.delegate = self
            p.enableRate = true          // Required before `rate` has any effect
            p.rate = Float(speed)
            p.prepareToPlay()
            player = p
            item = Item(id: id, title: title, duration: p.duration)
            miniPlayerDismissed = false
            currentTime = 0
            p.play()
            isPlaying = true
            startTicker()
        } catch {
            OshiLog.call.info("❌ Voice note playback failed: \(error)")
            VoiceMessageAudioRoute.shared.endPlayback()
            item = nil
            isPlaying = false
        }
    }

    func pause() {
        player?.pause()
        isPlaying = false
        stopTicker()
    }

    func resume() {
        guard let player else { return }
        player.rate = Float(speed)
        player.play()
        isPlaying = true
        miniPlayerDismissed = false
        startTicker()
    }

    func toggle() {
        isPlaying ? pause() : resume()
    }

    /// Back to the start and playing again — the mini player's replay.
    func replay() {
        guard let player else { return }
        player.currentTime = 0
        currentTime = 0
        player.rate = Float(speed)
        player.play()
        isPlaying = true
        startTicker()
    }

    func seek(toProgress p: Double) {
        guard let player, let item, item.duration > 0 else { return }
        let t = min(item.duration, max(0, p * item.duration))
        player.currentTime = t
        currentTime = t
    }

    /// __VOICE_SCRUB_2026_10_26__ Seek a note that may not be the loaded one.
    ///
    /// `seek(toProgress:)` above can only move the head of whatever is already
    /// in the player, so scrubbing the waveform of a note nobody had started
    /// did nothing at all — which is half of "we cannot click in the middle of
    /// the message". This loads the note first when it is not the active one.
    ///
    /// `startIfIdle` is what separates a tap from a drag: tapping the waveform
    /// means "play from here", while dragging it only moves the head and leaves
    /// a paused note paused.
    func seek(id: String,
              title: String,
              data: Data,
              toProgress p: Double,
              startIfIdle: Bool) {
        let clamped = min(1, max(0, p))

        // Already loaded: just move the head, and only then decide about playing.
        if item?.id == id, player != nil {
            seek(toProgress: clamped)
            if startIfIdle, !isPlaying { resume() }
            return
        }

        guard startIfIdle else {
            // A drag over a note that was never started has nothing to move.
            // Loading it silently here would make a scrub grab the audio route.
            return
        }

        play(id: id, title: title, data: data)
        // `play` is synchronous up to `AVAudioPlayer.play()`, so the head can be
        // placed right away — no need to wait for a callback.
        seek(toProgress: clamped)
    }

    /// The ✕: stop the audio and drop the note entirely.
    func close() {
        teardown()
        item = nil
        miniPlayerDismissed = false
    }

    /// Hide the floating player without stopping the audio is NOT offered on
    /// purpose — a user who closes it means "stop talking to me".
    func cycleSpeed() {
        speed = switch speed {
            case 1.0: 1.5
            case 1.5: 2.0
            default:  1.0
        }
        UserDefaults.standard.set(speed, forKey: voiceNoteSpeedKey)
        player?.rate = Float(speed)
    }

    // MARK: - Owner visibility

    func ownerAppeared(_ id: String) { visibleOwners.insert(id) }
    func ownerDisappeared(_ id: String) { visibleOwners.remove(id) }

    /// Reports the height of a bottom bar on the current screen. Writes are
    /// filtered: a measurement arrives on every layout pass, and an unchanged
    /// value must not re-publish or the pill re-renders while scrolling.
    func setBottomObstruction(_ height: CGFloat) {
        let rounded = (height * 2).rounded() / 2
        guard abs(rounded - bottomObstruction) > 0.5 else { return }
        bottomObstruction = rounded
    }

    func clearBottomObstruction() {
        guard bottomObstruction != 0 else { return }
        bottomObstruction = 0
    }

    // MARK: - Internals

    private func startTicker() {
        stopTicker()
        // 0.1 s is what the previous per-bubble players used; the waveform is
        // smoothed by its own animation so this is plenty.
        let t = Timer(timeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, let player = self.player else { return }
                self.currentTime = player.currentTime
            }
        }
        RunLoop.main.add(t, forMode: .common)
        ticker = t
    }

    private func stopTicker() {
        ticker?.invalidate()
        ticker = nil
    }

    private func teardown() {
        player?.stop()
        player = nil
        isPlaying = false
        currentTime = 0
        stopTicker()
        // Without this, proximity monitoring stays on and the screen blanks as
        // soon as the phone is near the ear, long after the note has finished.
        VoiceMessageAudioRoute.shared.endPlayback()
    }
}

extension VoiceNotePlayback: AVAudioPlayerDelegate {
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            // Finishing takes the floating player away on its own — that is the
            // "disappears at the end of playback" half of the requirement.
            self.teardown()
            self.item = nil
        }
    }
}
