import SwiftUI

// __VOICE_MINIPLAYER_2026_10_09__
//
// The compact player that follows a playing voice note out of its conversation.
// Installed once at the scene root, so it is reachable from every tab, every
// pushed screen and every sheet-free view without each one knowing about it.
//
// Rules it has to obey, in the owner's words: minimalist, adapts to every view
// and screen, never in the way of the buttons underneath, and it goes away by
// itself when the note ends or when the ✕ is tapped.
struct VoiceNoteMiniPlayerView: View {
    @ObservedObject private var playback = VoiceNotePlayback.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Clears a standard tab bar so the player never covers the tab buttons.
    /// Screens without a tab bar simply get it floating a little higher, which
    /// is the safe direction to be wrong in.
    private let tabBarClearance: CGFloat = 56

    var body: some View {
        ZStack(alignment: .bottom) {
            if playback.showsMiniPlayer, let item = playback.item {
                pill(item)
                    .padding(.horizontal, 12)
                    // Tab bar, plus whatever bottom bar the current screen
                    // reported. In a conversation that is the composer, so the
                    // pill sits above the text field and the send button
                    // instead of on top of them.
                    .padding(.bottom, tabBarClearance + playback.bottomObstruction)
                    .transition(reduceMotion
                                ? .opacity
                                : .move(edge: .bottom).combined(with: .opacity))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        .animation(.spring(response: 0.38, dampingFraction: 0.86),
                   value: playback.showsMiniPlayer)
        // Slide, don't jump, when a screen with a composer is pushed or popped
        // underneath the pill.
        .animation(.easeOut(duration: 0.22), value: playback.bottomObstruction)
        // Only the pill itself is tappable; the rest of the screen stays live.
        .allowsHitTesting(playback.showsMiniPlayer)
    }

    @ViewBuilder
    private func pill(_ item: VoiceNotePlayback.Item) -> some View {
        HStack(spacing: 10) {
            playPauseButton

            VStack(alignment: .leading, spacing: 5) {
                Text(item.title)
                    .font(.scaled(13, weight: .semibold))
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .foregroundStyle(.primary)

                progressLine
            }

            Text(remainingLabel(item))
                .font(.caption2)
                .monospacedDigit()
                .foregroundStyle(.secondary)
                // Fixed width: the label must not shuffle the layout every
                // tenth of a second as the digits change.
                .frame(width: 38, alignment: .trailing)

            replayButton
            speedButton
            closeButton
        }
        .padding(.leading, 8)
        .padding(.trailing, 10)
        .padding(.vertical, 8)
        .background(
            Capsule(style: .continuous)
                .fill(.regularMaterial)
                .shadow(color: .black.opacity(0.18), radius: 14, y: 6)
        )
        .overlay(
            Capsule(style: .continuous)
                .strokeBorder(Color.primary.opacity(0.07), lineWidth: 0.5)
        )
        // Caps the width on iPad and on the Mac window, where a full-width
        // pill would look like a broken toolbar.
        .frame(maxWidth: 520)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("audio.miniplayer".localized)
    }

    private var playPauseButton: some View {
        Button {
            #if os(iOS)
            Haptics.tap(.light)
            #endif
            playback.toggle()
        } label: {
            Image(systemName: playback.isPlaying ? "pause.fill" : "play.fill")
                .font(.scaled(13, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 32, height: 32)
                .background(Circle().fill(Theme.brand))
                .contentTransition(.opacity)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(playback.isPlaying ? "audio.pause".localized : "audio.play".localized)
    }

    private var replayButton: some View {
        Button {
            #if os(iOS)
            Haptics.tap(.light)
            #endif
            playback.replay()
        } label: {
            Image(systemName: "gobackward")
                .font(.scaled(13, weight: .semibold))
                .foregroundStyle(.secondary)
                .frame(width: 30, height: 30)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("audio.replay".localized)
    }

    private var speedButton: some View {
        Button {
            playback.cycleSpeed()
        } label: {
            Text(speedLabel)
                .font(.scaled(11, weight: .bold))
                .monospacedDigit()
                .foregroundStyle(Theme.brand)
                .frame(width: 34, height: 30)
                .background(
                    Capsule().fill(Theme.brand.opacity(0.12))
                )
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("audio.playback_speed".localized)
        .accessibilityValue(speedLabel)
    }

    private var closeButton: some View {
        Button {
            playback.close()
        } label: {
            Image(systemName: "xmark")
                .font(.scaled(11, weight: .bold))
                .foregroundStyle(.secondary)
                .frame(width: 30, height: 30)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("common.close".localized)
    }

    /// A 3 pt line rather than a Slider: a Slider's thumb is as tall as the
    /// whole pill and would fight the capsule for space.
    private var progressLine: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(Color.primary.opacity(0.12))
                Capsule()
                    .fill(Theme.brand)
                    .frame(width: max(2, geo.size.width * playback.progress))
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { v in
                        guard geo.size.width > 0 else { return }
                        playback.seek(toProgress: v.location.x / geo.size.width)
                    }
            )
        }
        .frame(height: 3)
    }

    private var speedLabel: String {
        playback.speed == playback.speed.rounded(.down)
            ? "\(Int(playback.speed))x"
            : String(format: "%.1fx", playback.speed)
    }

    private func remainingLabel(_ item: VoiceNotePlayback.Item) -> String {
        let left = max(0, item.duration - playback.currentTime)
        let s = Int(left.rounded())
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}

// MARK: - Declaring a bottom bar to the mini player

/// __VOICE_MINIPLAYER_2026_10_09__ Put this on the bar that sits at the bottom
/// of a screen — a chat's composer — so the floating voice-note pill rides
/// above it rather than over its buttons.
///
/// It measures rather than assumes: a composer grows with the text, with
/// Dynamic Type and with an attached reply preview, so any hard-coded
/// clearance would be wrong on most screens. The height is reported through
/// `VoiceNotePlayback`, which filters unchanged values so a scroll does not
/// re-publish on every layout pass.
private struct VoiceNotePlayerObstruction: ViewModifier {
    func body(content: Content) -> some View {
        content.background(
            GeometryReader { geo in
                Color.clear
                    .onAppear { VoiceNotePlayback.shared.setBottomObstruction(geo.size.height) }
                    .onChange(of: geo.size.height) { _, h in
                        VoiceNotePlayback.shared.setBottomObstruction(h)
                    }
                    .onDisappear { VoiceNotePlayback.shared.clearBottomObstruction() }
            }
        )
    }
}

extension View {
    /// Marks this view as the bottom bar the voice-note mini player must clear.
    func voiceNotePlayerObstruction() -> some View {
        modifier(VoiceNotePlayerObstruction())
    }
}
