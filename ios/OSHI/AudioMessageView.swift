import SwiftUI

// MARK: - Brand Color
private let appBrandColor = Theme.brand
import AVFoundation

// MARK: - Brand Color

// Define Message structure if not already defined elsewhere
struct Message: Identifiable, Codable {
    let id: String
    let content: String
    let timestamp: Date
    let isFromCurrentUser: Bool
    let senderPublicKey: String
    var mediaData: Data?
    var mediaType: String?
    
    init(id: String = UUID().uuidString,
         content: String,
         timestamp: Date = Date(),
         isFromCurrentUser: Bool,
         senderPublicKey: String,
         mediaData: Data? = nil,
         mediaType: String? = nil) {
        self.id = id
        self.content = content
        self.timestamp = timestamp
        self.isFromCurrentUser = isFromCurrentUser
        self.senderPublicKey = senderPublicKey
        self.mediaData = mediaData
        self.mediaType = mediaType
    }
}

struct AudioMessageView: View {
    let message: Message
    let isCurrentUser: Bool
    @State private var isPlaying = false
    @State private var playbackProgress: Double = 0
    @State private var currentTime: TimeInterval = 0
    @State private var audioPlayer: AVAudioPlayer?
    @State private var timer: Timer?
    @State private var showError = false
    @State private var errorMessage = ""

    // Global playback-speed preference shared across all audio messages.
    // Cycles 1.0x → 1.5x → 2.0x → 1.0x. Persisted via @AppStorage.
    @AppStorage("audioPlaybackSpeed") private var playbackSpeed: Double = 1.0
    
    var body: some View {
        VStack(alignment: isCurrentUser ? .trailing : .leading, spacing: 4) {
            // Voice effect indicator if present
            if let attachment = decodeAudioAttachment(from: message),
               let effectString = attachment.voiceEffect,
               let effect = AudioRecorderManager.VoiceEffect(rawValue: effectString),
               effect != .normal {
                HStack(spacing: 4) {
                    Image(systemName: effect.iconName)
                        .font(.caption2)
                    Text(effect.rawValue)
                        .font(.caption2)
                }
                .foregroundColor(.secondary)
                .padding(.horizontal, 8)
                .padding(.vertical, 2)
                .background(Color.gray.opacity(0.2))
                .cornerRadius(8)
            }
            
            HStack(spacing: 12) {
                // Play/Pause button
                Button(action: togglePlayback) {
                    Image(systemName: isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 32))
                        .foregroundColor(isCurrentUser ? .white : appBrandColor)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel(isPlaying ? "audio.pause".localized : "audio.play".localized)
                
                VStack(alignment: .leading, spacing: 4) {
                    // Waveform or progress bar
                    if let attachment = decodeAudioAttachment(from: message),
                       let waveformData = attachment.waveformData {
                        AudioWaveformView(
                            levels: waveformData,
                            progress: playbackProgress,
                            color: isCurrentUser ? .white : appBrandColor
                        )
                        .frame(width: 150, height: 30)
                        .accessibilityHidden(true)
                    } else {
                        // Fallback progress bar
                        GeometryReader { geometry in
                            ZStack(alignment: .leading) {
                                Rectangle()
                                    .fill(Color.gray.opacity(0.3))
                                    .frame(height: 4)
                                
                                Rectangle()
                                    .fill(isCurrentUser ? Color.white : appBrandColor)
                                    .frame(width: geometry.size.width * CGFloat(playbackProgress), height: 4)
                            }
                        }
                        .frame(width: 150, height: 4)
                    }
                    
                    // Duration
                    HStack {
                        Text(formatTime(currentTime))
                            .font(.caption2)
                        Spacer()
                        if let attachment = decodeAudioAttachment(from: message) {
                            Text(formatTime(attachment.duration))
                                .font(.caption2)
                        }
                    }
                    .foregroundColor(isCurrentUser ? .white.opacity(0.9) : .secondary)
                    .frame(width: 150)
                }

                // Playback-speed toggle: cycles 1x → 1.5x → 2x → 1x.
                // Persisted globally via @AppStorage so the last-used rate
                // applies to the next voice note too.
                Button(action: cyclePlaybackSpeed) {
                    Text(speedLabel)
                        .font(.caption2)
                        .fontWeight(.semibold)
                        .foregroundColor(isCurrentUser ? .white : appBrandColor)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(
                            Capsule()
                                .stroke(isCurrentUser ? Color.white.opacity(0.6) : appBrandColor.opacity(0.6), lineWidth: 1)
                        )
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("audio.playback_speed".localized)
                .accessibilityValue(speedLabel)
                .accessibilityHint("audio.playback_speed.hint".localized)
            }
            .padding(12)
            .background(
                RoundedRectangle(cornerRadius: 16)
                    .fill(isCurrentUser ? appBrandColor : Color.gray.opacity(0.2))
            )
            
            // Timestamp
            Text(formatTimestamp(message.timestamp))
                .font(.caption2)
                .foregroundColor(.secondary)
                .padding(.horizontal, 4)
        }
        .sensoryFeedback(.selection, trigger: isPlaying)
        .alert("common.error".localized, isPresented: $showError) {
            Button("common.ok".localized, role: .cancel) { }
        } message: {
            Text(errorMessage)
        }
    }
    
    private func decodeAudioAttachment(from message: Message) -> AudioRecorderManager.AudioAttachment? {
        guard let mediaData = message.mediaData,
              let attachment = try? JSONDecoder().decode(AudioRecorderManager.AudioAttachment.self, from: mediaData) else {
            // Fallback for legacy audio messages
            if let mediaData = message.mediaData {
                return AudioRecorderManager.AudioAttachment(
                    data: mediaData,
                    duration: 0,
                    voiceEffect: nil
                )
            }
            return nil
        }
        return attachment
    }
    
    private func togglePlayback() {
        if isPlaying {
            stopPlayback()
        } else {
            playAudio()
        }
    }
    
    private func playAudio() {
        guard let attachment = decodeAudioAttachment(from: message) else { return }
        
        do {
            // __PLAINTEXT_LEFTOVERS_2026_09_24__ Lecture depuis la mémoire: plus
            // de copie en clair sur disque (avant: tmp, supprimée seulement après
            // `duration + 1` s — jamais si la lecture était mise en pause).
            audioPlayer = try AVAudioPlayer(data: attachment.data, fileTypeHint: AVFileType.m4a.rawValue)
            audioPlayer?.enableRate = true                       // Required for `rate` to take effect
            audioPlayer?.rate = Float(playbackSpeed)             // Apply persisted speed
            audioPlayer?.prepareToPlay()
            audioPlayer?.play()
            
            isPlaying = true
            currentTime = 0
            
            // Start progress timer
            timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { _ in
                guard let player = audioPlayer else { return }
                currentTime = player.currentTime
                playbackProgress = player.currentTime / player.duration
                
                if !player.isPlaying {
                    stopPlayback()
                }
            }

        } catch {
            showError = true
            errorMessage = "Failed to play audio: \(error.localizedDescription)"
        }
    }
    
    private func stopPlayback() {
        audioPlayer?.stop()
        isPlaying = false
        timer?.invalidate()
        timer = nil
        playbackProgress = 0
        currentTime = 0
    }
    
    // MARK: - Playback speed

    /// Label for the speed button. Drops trailing ".0" so "1.0x" shows as "1x" etc.
    private var speedLabel: String {
        let trimmed = playbackSpeed.truncatingRemainder(dividingBy: 1) == 0
            ? String(Int(playbackSpeed))
            : String(format: "%.1f", playbackSpeed)
        return "\(trimmed)x"
    }

    /// Cycles 1.0 → 1.5 → 2.0 → 1.0. Applies live to the current player if playing.
    private func cyclePlaybackSpeed() {
        playbackSpeed = switch playbackSpeed {
            case 1.0: 1.5
            case 1.5: 2.0
            default:  1.0
        }
        // Apply immediately if audio is currently playing
        audioPlayer?.rate = Float(playbackSpeed)
    }

    private func formatTime(_ time: TimeInterval) -> String {
        let minutes = Int(time) / 60
        let seconds = Int(time) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
    
    private func formatTimestamp(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}

// Renamed to avoid conflict with WaveformView in other files
struct AudioWaveformView: View {
    let levels: [Float]
    let progress: Double
    let color: Color
    
    var body: some View {
        GeometryReader { geometry in
            HStack(spacing: 2) {
                ForEach(0..<levels.count, id: \.self) { index in
                    let height = CGFloat(abs(levels[index])) * geometry.size.height
                    let isPassed = Double(index) / Double(levels.count) <= progress
                    
                    RoundedRectangle(cornerRadius: 1)
                        .fill(isPassed ? color : color.opacity(0.3))
                        .frame(width: 2, height: max(4, height))
                }
            }
        }
    }
}
