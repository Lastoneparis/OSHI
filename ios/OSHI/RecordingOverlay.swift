//
//  RecordingOverlay.swift
//  Standalone file for audio recording overlay
//  Add this file to your project to fix "Cannot find 'RecordingOverlay' in scope" error
//

import SwiftUI

// MARK: - Recording Overlay

struct RecordingOverlay: View {
    @ObservedObject var audioRecorder: AudioRecorderManager
    let onCancel: () -> Void
    
    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            
            // Waveform visualization
            WaveformView(
                levels: audioRecorder.audioLevels.isEmpty ? Array(repeating: 0.1, count: 50) : audioRecorder.audioLevels,
                progress: 1.0,
                color: .red
            )
            .frame(height: 60)
            .padding(.horizontal, 40)
            
            // Duration
            Text(formatDuration(audioRecorder.recordingDuration))
                .font(.scaled(48, weight: .light, design: .rounded))
                .foregroundColor(.white)
            
            // Cancel button
            Button(action: onCancel) {
                Text("common.cancel".localized)
                    .font(.headline)
                    .foregroundColor(.white)
                    .padding(.horizontal, 30)
                    .padding(.vertical, 15)
                    .background(Color.red.opacity(0.8))
                    .cornerRadius(25)
            }
            .padding(.bottom, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.opacity(0.9))
    }
    
    private func formatDuration(_ duration: TimeInterval?) -> String {
        guard let duration = duration else { return "0:00" }
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

// MARK: - Waveform View

struct WaveformView: View {
    let levels: [Float]
    let progress: Double
    let color: Color
    
    var body: some View {
        GeometryReader { geometry in
            HStack(alignment: .center, spacing: 2) {
                ForEach(Array(levels.enumerated()), id: \.offset) { index, level in
                    let barWidth = (geometry.size.width - CGFloat(levels.count - 1) * 2) / CGFloat(levels.count)
                    let barHeight = max(2, CGFloat(level) * geometry.size.height * 0.8)
                    let isPast = Double(index) / Double(levels.count) < progress
                    
                    RoundedRectangle(cornerRadius: 1)
                        .fill(isPast ? color : color.opacity(0.3))
                        .frame(width: barWidth, height: barHeight)
                }
            }
        }
    }
}
