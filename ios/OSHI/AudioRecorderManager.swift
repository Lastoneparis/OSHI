import SwiftUI
import AVFoundation
import Combine

// MARK: - Audio Recorder Manager
class AudioRecorderManager: NSObject, ObservableObject, AVAudioRecorderDelegate, AVAudioPlayerDelegate {
    @Published var isRecording = false
    @Published var hasRecording = false
    @Published var recordingDuration: TimeInterval?
    @Published var isPlaying = false
    @Published var audioLevels: [Float] = Array(repeating: 0.0, count: 20)
    
    private var audioRecorder: AVAudioRecorder?
    private var audioPlayer: AVAudioPlayer?
    private var recordingSession: AVAudioSession?
    private var recordingTimer: Timer?
    private var recordingStartTime: Date?
    private var levelTimer: Timer?
    
    var recordingURL: URL? {
        return audioRecorder?.url
    }
    
    override init() {
        super.init()
        // 🔧 FIX: Don't activate audio session on init - only request permission
        requestRecordingPermission()
    }
    
    /// Request recording permission without activating audio session
    private func requestRecordingPermission() {
        // 📸 Skip during App Store screenshot capture so the mic-permission
        // alert never covers the chat interior.
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-OSHIScreens") { return }
        #endif
        // Request recording permission - iOS 17+ compatible
        if #available(iOS 17.0, *) {
            AVAudioApplication.requestRecordPermission { allowed in
                if !allowed {
                    OshiLog.call.info("Recording permission denied")
                }
            }
        } else {
            AVAudioSession.sharedInstance().requestRecordPermission { allowed in
                if !allowed {
                    OshiLog.call.info("Recording permission denied")
                }
            }
        }
    }
    
    /// Setup and activate audio session only when needed for recording
    private func activateRecordingSession() throws {
        recordingSession = AVAudioSession.sharedInstance()
        // 🔧 FIX: Use .mixWithOthers option to not interrupt other audio when possible
        // For recording, we need .playAndRecord but only activate when actually recording
        try recordingSession?.setCategory(.playAndRecord, mode: .default, options: [.allowBluetoothA2DP, .defaultToSpeaker])
        try recordingSession?.setActive(true, options: .notifyOthersOnDeactivation)
        OshiLog.call.info("🎙️ Recording session activated")
    }
    
    /// Deactivate audio session when done recording/playing
    private func deactivateRecordingSession() {
        do {
            try recordingSession?.setActive(false, options: .notifyOthersOnDeactivation)
            OshiLog.call.info("🎙️ Recording session deactivated - other audio can resume")
        } catch {
            OshiLog.call.info("Failed to deactivate recording session: \(error)")
        }
    }
    
    private func setupRecordingSession() {
        // 🔧 FIX: This is now a no-op - session is activated only when needed
        // Kept for backward compatibility if called elsewhere
    }
    
    func startRecording() throws {
        // 🔧 FIX: Activate audio session only when starting to record
        try activateRecordingSession()
        
        // __PLAINTEXT_LEFTOVERS_2026_09_24__ Avant: `Documents/recording_<uuid>.m4a`,
        // en clair, sauvegardé, JAMAIS supprimé — chaque note vocale restait sur
        // disque. Maintenant tmp protégé, supprimé dès que les octets sont lus
        // (`stopRecording`), balayé au lancement suivant sinon.
        let audioFilename = MediaPlaintextTemp.url(fileName: "recording.m4a")
        // Une capture pilotée par la montre tourne téléphone VERROUILLÉ: le
        // fichier doit rester lisible à l'arrêt (classe « après 1er déverrouillage »
        // héritée du sous-dossier, au lieu de `completeUnlessOpen`).
        try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                                               ofItemAtPath: audioFilename.deletingLastPathComponent().path)
        
        let settings = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
        ]
        
        audioRecorder = try AVAudioRecorder(url: audioFilename, settings: settings)
        audioRecorder?.delegate = self
        audioRecorder?.isMeteringEnabled = true
        audioRecorder?.record()
        
        isRecording = true
        hasRecording = false
        recordingStartTime = Date()
        
        // Start timer to update duration
        recordingTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { _ in
            if let startTime = self.recordingStartTime {
                self.recordingDuration = Date().timeIntervalSince(startTime)
            }
        }
        
        // Start level monitoring
        levelTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
            guard let self = self, let recorder = self.audioRecorder, recorder.isRecording else { return }
            recorder.updateMeters()
            let level = recorder.averagePower(forChannel: 0)
            let normalizedLevel = max(0.0, min(1.0, (level + 50.0) / 50.0))
            
            // Update levels array
            var newLevels = self.audioLevels
            newLevels.removeFirst()
            newLevels.append(normalizedLevel)
            self.audioLevels = newLevels
        }
    }
    
    func stopRecording() async throws -> AudioAttachment {
        return try await withCheckedThrowingContinuation { continuation in
            guard let recorder = audioRecorder, recorder.isRecording else {
                continuation.resume(throwing: NSError(domain: "AudioRecorder", code: 1,
                    userInfo: [NSLocalizedDescriptionKey: "No active recording"]))
                return
            }
            
            recorder.stop()
            
            DispatchQueue.main.async {
                self.isRecording = false
                self.hasRecording = true
                self.recordingTimer?.invalidate()
                self.levelTimer?.invalidate()
                
                if let startTime = self.recordingStartTime {
                    self.recordingDuration = Date().timeIntervalSince(startTime)
                }
                
                // Create AudioAttachment
                guard let url = self.recordingURL else {
                    // 🔧 FIX: Deactivate session on error too
                    self.deactivateRecordingSession()
                    continuation.resume(throwing: NSError(domain: "AudioRecorder", code: 2,
                        userInfo: [NSLocalizedDescriptionKey: "Recording URL not found"]))
                    return
                }
                
                do {
                    let audioData = try Data(contentsOf: url)
                    MediaPlaintextTemp.remove(url)  // __PLAINTEXT_LEFTOVERS_2026_09_24__ octets en mémoire
                    let duration = self.recordingDuration ?? 0
                    let attachment = AudioAttachment(
                        data: audioData,
                        duration: duration,
                        voiceEffect: nil,
                        waveformData: nil
                    )
                    // 🔧 FIX: Deactivate session after successful recording
                    // Note: We keep session active if user might want to play back
                    continuation.resume(returning: attachment)
                } catch {
                    MediaPlaintextTemp.remove(url)  // __PLAINTEXT_LEFTOVERS_2026_09_24__
                    // 🔧 FIX: Deactivate session on error
                    self.deactivateRecordingSession()
                    continuation.resume(throwing: error)
                }
            }
        }
    }
    
    func playRecording() throws {
        // 🔧 FIX: Setup playback-friendly audio session
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
        try session.setActive(true)
        
        guard let url = recordingURL else { return }
        audioPlayer = try AVAudioPlayer(contentsOf: url)
        audioPlayer?.delegate = self
        audioPlayer?.play()
        isPlaying = true
    }
    
    func stopPlayback() {
        audioPlayer?.stop()
        isPlaying = false
        // 🔧 FIX: Deactivate session when done playing
        deactivateRecordingSession()
    }
    
    func reset() {
        // Stop recording if active (synchronous for cleanup)
        if isRecording {
            audioRecorder?.stop()
            isRecording = false
            recordingTimer?.invalidate()
            levelTimer?.invalidate()
        }
        stopPlayback()
        
        // Delete the recording file
        if let url = recordingURL {
            MediaPlaintextTemp.remove(url)  // __PLAINTEXT_LEFTOVERS_2026_09_24__ + son sous-dossier
        }
        
        audioRecorder = nil
        audioPlayer = nil
        hasRecording = false
        recordingDuration = nil
        recordingStartTime = nil
        
        // 🔧 FIX: Deactivate session on reset to allow other audio to resume
        deactivateRecordingSession()
    }
    
    // AVAudioPlayerDelegate
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        isPlaying = false
        // 🔧 FIX: Deactivate session when playback finishes to allow other audio to resume
        deactivateRecordingSession()
    }
    
    // MARK: - Audio Attachment Structure
    struct AudioAttachment: Codable {
        let data: Data
        let duration: TimeInterval
        let voiceEffect: String?
        let waveformData: [Float]? // Add this for waveform visualization
        
        init(data: Data, duration: TimeInterval, voiceEffect: String? = nil, waveformData: [Float]? = nil) {
            self.data = data
            self.duration = duration
            self.voiceEffect = voiceEffect
            self.waveformData = waveformData ?? AudioAttachment.generateWaveform(from: data)
        }
        
        var fileSizeFormatted: String {
            let kb = Double(data.count) / 1000.0
            if kb < 1000 {
                return String(format: "%.1f KB", kb)
            } else {
                return String(format: "%.1f MB", kb / 1000.0)
            }
        }
        
        var fileSize: Int {
            return data.count
        }
        
        var durationFormatted: String {
            let minutes = Int(duration) / 60
            let seconds = Int(duration) % 60
            return String(format: "%d:%02d", minutes, seconds)
        }
        
        static func generateWaveform(from data: Data) -> [Float] {
            let sampleCount = 50
            
            // Try to analyze actual audio samples from the file
            if let waveform = generateWaveformFromAudioFile(data: data, sampleCount: sampleCount) {
                return waveform
            }
            
            // Fallback: analyze raw data with improved algorithm
            let rawWaveform = generateWaveformFromRawData(data: data, sampleCount: sampleCount)
            
            // If the waveform is too flat (all similar values), generate a pseudo-random one based on data
            let variance = calculateVariance(rawWaveform)
            if variance < 0.01 {
                OshiLog.call.info("⚠️ Waveform too flat, generating visual representation")
                return generatePseudoRandomWaveform(from: data, sampleCount: sampleCount)
            }
            
            return rawWaveform
        }
        
        private static func calculateVariance(_ values: [Float]) -> Float {
            guard !values.isEmpty else { return 0 }
            let mean = values.reduce(0, +) / Float(values.count)
            let squaredDiffs = values.map { ($0 - mean) * ($0 - mean) }
            return squaredDiffs.reduce(0, +) / Float(values.count)
        }
        
        private static func generatePseudoRandomWaveform(from data: Data, sampleCount: Int) -> [Float] {
            // Generate a deterministic but varied waveform based on data hash
            var waveform: [Float] = []
            
            // Use first bytes of data as seed for variation
            var seed: UInt32 = 12345
            for (index, byte) in data.prefix(100).enumerated() {
                seed = seed &+ UInt32(byte) &* UInt32(index + 1)
            }
            
            // Simple pseudo-random generator based on data
            for i in 0..<sampleCount {
                seed = seed &* 1103515245 &+ 12345
                let random = Float((seed >> 16) & 0x7FFF) / Float(0x7FFF)
                
                // Create a wave-like pattern with some randomness
                let baseWave = sin(Float(i) * 0.3) * 0.3 + 0.5
                let variation = (random - 0.5) * 0.4
                let value = max(0.15, min(0.95, baseWave + variation))
                waveform.append(value)
            }
            
            return waveform
        }
        
        private static func generateWaveformFromAudioFile(data: Data, sampleCount: Int) -> [Float]? {
            // Try multiple extensions for the temp file
            let extensions = ["m4a", "caf", "wav", "aac", "mp4"]
            
            for ext in extensions {
                let tempURL = MediaPlaintextTemp.scratchURL(ext: ext)  // __PLAINTEXT_LEFTOVERS_2026_09_24__
                
                do {
                    defer { try? FileManager.default.removeItem(at: tempURL) }
                    // UFA: calculée aussi à l'arrêt d'une capture montre, téléphone verrouillé.
                    try data.write(to: tempURL, options: [.completeFileProtectionUntilFirstUserAuthentication])
                    
                    let audioFile = try AVAudioFile(forReading: tempURL)
                    let format = audioFile.processingFormat
                    let frameCount = AVAudioFrameCount(audioFile.length)
                    
                    guard frameCount > 0 else { continue }
                    
                    guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount) else {
                        continue
                    }
                    
                    try audioFile.read(into: buffer)
                    
                    guard let floatChannelData = buffer.floatChannelData else { continue }
                    
                    let channelData = floatChannelData[0]
                    let framesPerSample = max(1, Int(frameCount) / sampleCount)
                    
                    var waveform: [Float] = []
                    
                    for i in 0..<sampleCount {
                        let startFrame = i * framesPerSample
                        let endFrame = min(startFrame + framesPerSample, Int(frameCount))
                        
                        var maxAmplitude: Float = 0
                        for frame in startFrame..<endFrame {
                            let amplitude = abs(channelData[frame])
                            if amplitude > maxAmplitude {
                                maxAmplitude = amplitude
                            }
                        }
                        
                        // Normalize to 0.15-0.95 range for better visualization
                        let normalized = min(0.95, max(0.15, maxAmplitude * 3))
                        waveform.append(normalized)
                    }
                    
                    // Check if waveform has meaningful variation
                    let variance = calculateVariance(waveform)
                    if variance > 0.005 {
                        OshiLog.call.info("✅ Generated waveform from audio file (\(ext)): \(waveform.count) samples, variance: \(variance)")
                        return waveform
                    }
                    
                } catch {
                    // Try next extension
                    continue
                }
            }
            
            OshiLog.call.info("⚠️ Could not analyze audio file for waveform with any format")
            return nil
        }
        
        private static func generateWaveformFromRawData(data: Data, sampleCount: Int) -> [Float] {
            var waveform: [Float] = []
            let bytesPerSample = max(1, data.count / sampleCount)
            
            for i in 0..<sampleCount {
                let startIdx = i * bytesPerSample
                let endIdx = min(startIdx + bytesPerSample, data.count)
                
                if startIdx < data.count && startIdx < endIdx {
                    let sampleData = data[startIdx..<endIdx]
                    
                    // Calculate RMS (root mean square) for better amplitude representation
                    var sumSquared: Float = 0
                    var maxVal: Float = 0
                    for byte in sampleData {
                        let centered = Float(byte) - 128.0
                        sumSquared += centered * centered
                        maxVal = max(maxVal, abs(centered))
                    }
                    
                    let rms = sqrt(sumSquared / Float(sampleData.count))
                    // Combine RMS and max for better visual
                    let combined = (rms / 64.0 + maxVal / 128.0) / 2.0
                    // Normalize to 0.15-0.95 range
                    let normalized = min(0.95, max(0.15, combined * 2))
                    waveform.append(normalized)
                } else {
                    waveform.append(0.3)
                }
            }
            
            return waveform.isEmpty ? Array(repeating: 0.5, count: sampleCount) : waveform
        }
    }
    
    // MARK: - Voice Effect Enum
    enum VoiceEffect: String, CaseIterable, Equatable {
        case normal = "Normal"  // Add normal/no effect option
        case robot = "Robot"
        case alien = "Alien"
        case deep = "Deep"
        case chipmunk = "Chipmunk"
        case echo = "Echo"
        case reverb = "Reverb"
        
        var iconName: String {
            switch self {
            case .normal: return "mic"
            case .robot: return "cpu"
            case .alien: return "antenna.radiowaves.left.and.right"
            case .deep: return "waveform"
            case .chipmunk: return "hare"
            case .echo: return "dot.radiowaves.left.and.right"
            case .reverb: return "building.columns"
            }
        }
        
        var icon: String { // Add this computed property for compatibility
            return iconName
        }
        
        var localizedName: String {
            return rawValue // Can be localized later with NSLocalizedString
        }
        
        var description: String {
            switch self {
            case .normal: return "Original voice"
            case .robot: return "Metallic and distorted"
            case .alien: return "High-pitched and otherworldly"
            case .deep: return "Low and powerful"
            case .chipmunk: return "Fast and squeaky"
            case .echo: return "Repeating echo effect"
            case .reverb: return "Cathedral-like ambience"
            }
        }
    }
    
    // MARK: - Apply Voice Effect Method
    func applyVoiceEffect(_ effect: VoiceEffect, to attachment: AudioAttachment) async throws -> AudioAttachment {
        guard effect != .normal else {
            // Return original attachment with effect marked as normal
            return AudioAttachment(
                data: attachment.data,
                duration: attachment.duration,
                voiceEffect: effect.rawValue,
                waveformData: attachment.waveformData
            )
        }
        
        OshiLog.call.info("🎨 Applying \(effect.rawValue) effect to audio...")
        OshiLog.call.info("   Input size: \(attachment.data.count) bytes")
        OshiLog.call.info("   Duration: \(attachment.duration) seconds")
        
        // Save audio data to temporary file
        let tempInputURL = MediaPlaintextTemp.scratchURL(ext: "m4a")  // __PLAINTEXT_LEFTOVERS_2026_09_24__
        
        try attachment.data.write(to: tempInputURL, options: [.completeFileProtectionUnlessOpen])
        OshiLog.call.info("   ✅ Wrote temp input file: \(tempInputURL.lastPathComponent)")
        
        do {
            let processedURL = try await processAudioWithEffect(effect, inputURL: tempInputURL)
            OshiLog.call.info("   ✅ Effect processed, output file: \(processedURL.lastPathComponent)")
            
            let processedData = try Data(contentsOf: processedURL)
            OshiLog.call.info("   ✅ Read processed data: \(processedData.count) bytes")
            
            OshiLog.call.info("✅ Effect applied successfully!")
            OshiLog.call.info("   Original size: \(attachment.data.count) bytes")
            OshiLog.call.info("   Processed size: \(processedData.count) bytes")
            OshiLog.call.info("   Difference: \(processedData.count - attachment.data.count) bytes")
            
            // Clean up temp files
            try? FileManager.default.removeItem(at: tempInputURL)
            try? FileManager.default.removeItem(at: processedURL)
            OshiLog.call.info("   ✅ Cleaned up temp files")
            
            // Return new attachment with processed data and effect marked
            return AudioAttachment(
                data: processedData,
                duration: attachment.duration,
                voiceEffect: effect.rawValue,
                waveformData: attachment.waveformData
            )
        } catch {
            OshiLog.call.info("❌ Failed to apply effect: \(error)")
            OshiLog.call.info("   Error details: \(error.localizedDescription)")
            try? FileManager.default.removeItem(at: tempInputURL)
            throw error
        }
    }
    
    // MARK: - Process Audio With Effect (Offline Rendering - 10-50x faster than real-time)
    private func processAudioWithEffect(_ effect: VoiceEffect, inputURL: URL) async throws -> URL {
        guard effect != .normal else {
            return inputURL
        }
        
        OshiLog.call.info("   🎛️ Processing with \(effect.rawValue) effect (OFFLINE mode)...")
        
        let processedURL: URL
        
        switch effect {
        case .normal:
            processedURL = inputURL
        case .robot:
            OshiLog.call.info("   🤖 Applying robot effect...")
            processedURL = try await applyRobotEffect(to: inputURL)
        case .alien:
            OshiLog.call.info("   👽 Applying alien effect...")
            processedURL = try await applyAlienEffect(to: inputURL)
        case .deep:
            OshiLog.call.info("   🎵 Applying deep effect...")
            processedURL = try await applyDeepEffect(to: inputURL)
        case .chipmunk:
            OshiLog.call.info("   🐿️ Applying chipmunk effect...")
            processedURL = try await applyChipmunkEffect(to: inputURL)
        case .echo:
            OshiLog.call.info("   🔊 Applying echo effect...")
            processedURL = try await applyEchoEffect(to: inputURL)
        case .reverb:
            OshiLog.call.info("   🏛️ Applying reverb effect...")
            processedURL = try await applyReverbEffect(to: inputURL)
        }
        
        OshiLog.call.info("   ✅ Effect method completed")
        return processedURL
    }
    
    // MARK: - Offline Rendering Engine (shared by all effects)
    /// Renders audio through the engine's effect chain offline (as fast as CPU allows).
    /// The engine must already have nodes attached and connected, and be in manual rendering mode.
    private func renderOffline(engine: AVAudioEngine, player: AVAudioPlayerNode, inputFile: AVAudioFile, outputFile: AVAudioFile, format: AVAudioFormat) throws {
        let totalFrames = AVAudioFrameCount(inputFile.length)
        let renderBlockSize: AVAudioFrameCount = 4096
        
        // Schedule the file and start playback within the offline engine
        player.scheduleFile(inputFile, at: nil)
        player.play()
        
        let outputBuffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: renderBlockSize)!
        var framesRendered: AVAudioFrameCount = 0
        // For effects that change rate (deep=0.8, chipmunk=1.5, alien=1.2),
        // the output length differs from input. Render until engine produces silence.
        // Use a generous upper bound: 2x the input length (for rate=0.5 slowdown).
        let maxFrames = totalFrames * 2
        
        let startTime = CFAbsoluteTimeGetCurrent()
        
        while framesRendered < maxFrames {
            try Task.checkCancellation()
            
            let status = try engine.renderOffline(renderBlockSize, to: outputBuffer)
            
            switch status {
            case .success:
                try outputFile.write(from: outputBuffer)
                framesRendered += outputBuffer.frameLength
            case .insufficientDataFromInputNode:
                // Player ran out of data - we're done
                // Write any remaining buffer if it has data
                if outputBuffer.frameLength > 0 {
                    try outputFile.write(from: outputBuffer)
                }
                let elapsed = CFAbsoluteTimeGetCurrent() - startTime
                let speedup = (Double(totalFrames) / format.sampleRate) / max(elapsed, 0.001)
                OshiLog.call.info("   ⚡ Offline render complete: \(framesRendered) frames in \(String(format: "%.2f", elapsed))s (\(String(format: "%.1f", speedup))x faster)")
                return
            case .cannotDoInCurrentContext:
                // Temporary issue, retry
                continue
            case .error:
                throw NSError(domain: "AudioProcessing", code: 3,
                             userInfo: [NSLocalizedDescriptionKey: "Offline render error"])
            @unknown default:
                break
            }
        }
        
        let elapsed = CFAbsoluteTimeGetCurrent() - startTime
        OshiLog.call.info("   ⚡ Offline render complete (max frames reached): \(framesRendered) frames in \(String(format: "%.2f", elapsed))s")
    }
    
    // MARK: - Audio Effects Implementation
    
    private func applyRobotEffect(to inputURL: URL) async throws -> URL {
        OshiLog.call.info("   🤖 Starting robot effect (offline)...")
        let inputFile = try AVAudioFile(forReading: inputURL)
        let format = inputFile.processingFormat
        
        let outputURL = MediaPlaintextTemp.scratchURL(ext: "caf")  // __PLAINTEXT_LEFTOVERS_2026_09_24__
        
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        let distortion = AVAudioUnitDistortion()
        let pitchEffect = AVAudioUnitTimePitch()
        
        engine.attach(player)
        engine.attach(distortion)
        engine.attach(pitchEffect)
        
        distortion.loadFactoryPreset(.multiDistortedCubed)
        distortion.wetDryMix = 50
        pitchEffect.pitch = -200
        
        engine.connect(player, to: distortion, format: format)
        engine.connect(distortion, to: pitchEffect, format: format)
        engine.connect(pitchEffect, to: engine.mainMixerNode, format: format)
        
        try engine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: 4096)
        try engine.start()
        
        let outputFile = try AVAudioFile(
            forWriting: outputURL,
            settings: [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVSampleRateKey: format.sampleRate,
                AVNumberOfChannelsKey: format.channelCount,
                AVLinearPCMBitDepthKey: 32,
                AVLinearPCMIsFloatKey: true,
                AVLinearPCMIsNonInterleaved: false
            ],
            commonFormat: .pcmFormatFloat32,
            interleaved: false
        )
        
        try renderOffline(engine: engine, player: player, inputFile: inputFile, outputFile: outputFile, format: format)
        engine.stop()
        
        OshiLog.call.info("   🔄 Converting to M4A...")
        let m4aURL = try await convertToM4A(from: outputURL)
        OshiLog.call.info("   ✅ Conversion complete!")
        return m4aURL
    }
    
    private func applyAlienEffect(to inputURL: URL) async throws -> URL {
        OshiLog.call.info("   👽 Starting alien effect (offline)...")
        let inputFile = try AVAudioFile(forReading: inputURL)
        let format = inputFile.processingFormat
        
        let outputURL = MediaPlaintextTemp.scratchURL(ext: "caf")  // __PLAINTEXT_LEFTOVERS_2026_09_24__
        
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        let pitchEffect = AVAudioUnitTimePitch()
        let reverb = AVAudioUnitReverb()
        
        engine.attach(player)
        engine.attach(pitchEffect)
        engine.attach(reverb)
        
        pitchEffect.pitch = 800
        pitchEffect.rate = 1.2
        reverb.loadFactoryPreset(.largeHall)
        reverb.wetDryMix = 30
        
        engine.connect(player, to: pitchEffect, format: format)
        engine.connect(pitchEffect, to: reverb, format: format)
        engine.connect(reverb, to: engine.mainMixerNode, format: format)
        
        try engine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: 4096)
        try engine.start()
        
        let outputFile = try AVAudioFile(
            forWriting: outputURL,
            settings: inputFile.fileFormat.settings,
            commonFormat: format.commonFormat,
            interleaved: format.isInterleaved
        )
        
        try renderOffline(engine: engine, player: player, inputFile: inputFile, outputFile: outputFile, format: format)
        engine.stop()
        
        OshiLog.call.info("   🔄 Converting to M4A...")
        let m4aURL = try await convertToM4A(from: outputURL)
        OshiLog.call.info("   ✅ Conversion complete!")
        return m4aURL
    }
    
    private func applyDeepEffect(to inputURL: URL) async throws -> URL {
        OshiLog.call.info("   🎵 Starting deep effect (offline)...")
        let inputFile = try AVAudioFile(forReading: inputURL)
        let format = inputFile.processingFormat
        
        let outputURL = MediaPlaintextTemp.scratchURL(ext: "caf")  // __PLAINTEXT_LEFTOVERS_2026_09_24__
        
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        let pitchEffect = AVAudioUnitTimePitch()
        
        engine.attach(player)
        engine.attach(pitchEffect)
        
        pitchEffect.pitch = -500
        pitchEffect.rate = 0.8
        
        engine.connect(player, to: pitchEffect, format: format)
        engine.connect(pitchEffect, to: engine.mainMixerNode, format: format)
        
        try engine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: 4096)
        try engine.start()
        
        let outputFile = try AVAudioFile(
            forWriting: outputURL,
            settings: inputFile.fileFormat.settings,
            commonFormat: format.commonFormat,
            interleaved: format.isInterleaved
        )
        
        try renderOffline(engine: engine, player: player, inputFile: inputFile, outputFile: outputFile, format: format)
        engine.stop()
        
        OshiLog.call.info("   🔄 Converting to M4A...")
        let m4aURL = try await convertToM4A(from: outputURL)
        OshiLog.call.info("   ✅ Conversion complete!")
        return m4aURL
    }
    
    private func applyChipmunkEffect(to inputURL: URL) async throws -> URL {
        OshiLog.call.info("   🐿️ Starting chipmunk effect (offline)...")
        let inputFile = try AVAudioFile(forReading: inputURL)
        let format = inputFile.processingFormat
        
        let outputURL = MediaPlaintextTemp.scratchURL(ext: "caf")  // __PLAINTEXT_LEFTOVERS_2026_09_24__
        
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        let pitchEffect = AVAudioUnitTimePitch()
        
        engine.attach(player)
        engine.attach(pitchEffect)
        
        pitchEffect.pitch = 1000
        pitchEffect.rate = 1.5
        
        engine.connect(player, to: pitchEffect, format: format)
        engine.connect(pitchEffect, to: engine.mainMixerNode, format: format)
        
        try engine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: 4096)
        try engine.start()
        
        let outputFile = try AVAudioFile(
            forWriting: outputURL,
            settings: inputFile.fileFormat.settings,
            commonFormat: format.commonFormat,
            interleaved: format.isInterleaved
        )
        
        try renderOffline(engine: engine, player: player, inputFile: inputFile, outputFile: outputFile, format: format)
        engine.stop()
        
        return try await convertToM4A(from: outputURL)
    }
    
    private func applyEchoEffect(to inputURL: URL) async throws -> URL {
        OshiLog.call.info("   🔊 Starting echo effect (offline)...")
        let inputFile = try AVAudioFile(forReading: inputURL)
        let format = inputFile.processingFormat
        
        let outputURL = MediaPlaintextTemp.scratchURL(ext: "caf")  // __PLAINTEXT_LEFTOVERS_2026_09_24__
        
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        let delay = AVAudioUnitDelay()
        
        engine.attach(player)
        engine.attach(delay)
        
        delay.delayTime = 0.3
        delay.feedback = 50
        delay.wetDryMix = 40
        
        engine.connect(player, to: delay, format: format)
        engine.connect(delay, to: engine.mainMixerNode, format: format)
        
        try engine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: 4096)
        try engine.start()
        
        let outputFile = try AVAudioFile(
            forWriting: outputURL,
            settings: inputFile.fileFormat.settings,
            commonFormat: format.commonFormat,
            interleaved: format.isInterleaved
        )
        
        try renderOffline(engine: engine, player: player, inputFile: inputFile, outputFile: outputFile, format: format)
        engine.stop()
        
        return try await convertToM4A(from: outputURL)
    }
    
    private func applyReverbEffect(to inputURL: URL) async throws -> URL {
        OshiLog.call.info("   🏛️ Starting reverb effect (offline)...")
        let inputFile = try AVAudioFile(forReading: inputURL)
        let format = inputFile.processingFormat
        
        let outputURL = MediaPlaintextTemp.scratchURL(ext: "caf")  // __PLAINTEXT_LEFTOVERS_2026_09_24__
        
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        let reverb = AVAudioUnitReverb()
        
        engine.attach(player)
        engine.attach(reverb)
        
        reverb.loadFactoryPreset(.cathedral)
        reverb.wetDryMix = 60
        
        engine.connect(player, to: reverb, format: format)
        engine.connect(reverb, to: engine.mainMixerNode, format: format)
        
        try engine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: 4096)
        try engine.start()
        
        let outputFile = try AVAudioFile(
            forWriting: outputURL,
            settings: inputFile.fileFormat.settings,
            commonFormat: format.commonFormat,
            interleaved: format.isInterleaved
        )
        
        try renderOffline(engine: engine, player: player, inputFile: inputFile, outputFile: outputFile, format: format)
        engine.stop()
        
        return try await convertToM4A(from: outputURL)
    }
    
    private func convertToM4A(from inputURL: URL) async throws -> URL {
        let outputURL = MediaPlaintextTemp.scratchURL(ext: "m4a")  // __PLAINTEXT_LEFTOVERS_2026_09_24__
        
        let asset = AVAsset(url: inputURL)
        guard let exportSession = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            throw NSError(domain: "AudioProcessing", code: 2,
                         userInfo: [NSLocalizedDescriptionKey: "Could not create export session"])
        }
        
        exportSession.outputURL = outputURL
        exportSession.outputFileType = .m4a
        
        // ✅ Use async/await instead of semaphore to avoid priority inversion
        await withCheckedContinuation { continuation in
            exportSession.exportAsynchronously {
                continuation.resume()
            }
        }
        
        if exportSession.status == .completed {
            // Clean up temp file
            try? FileManager.default.removeItem(at: inputURL)
            return outputURL
        } else {
            // Log detailed error
            if let error = exportSession.error {
                OshiLog.call.info("   ❌ Export session error: \(error)")
                OshiLog.call.info("   ❌ Export status: \(exportSession.status.rawValue)")
            }
            
            // Try to use the CAF file directly if conversion fails
            OshiLog.call.info("   ⚠️ Conversion failed, returning CAF file directly")
            return inputURL
        }
    }
}

// MARK: - Audio Player Manager
class AudioPlayerManager: NSObject, ObservableObject, AVAudioPlayerDelegate {
    @Published var isPlaying = false
    @Published var currentTime: Double = 0.0
    /// __VOICE_ROUTE_2026_09_12__ Défaut: HAUT-PARLEUR.
    ///
    /// C'était `false`, donc l'écouteur, et c'est le bug: un message vocal reçu
    /// en tête-à-tête sortait du petit haut-parleur du haut pendant que le même
    /// message en groupe sortait du gros. L'utilisateur n'entend rien et conclut
    /// que le son est cassé. WhatsApp, Signal et iMessage partent tous du
    /// haut-parleur; le passage à l'écouteur se fait au capteur de proximité,
    /// pas par défaut. Voir `VoiceMessageAudioRoute`.
    @Published var useSpeaker = true
    
    private var audioPlayer: AVAudioPlayer?
    private var updateTimer: Timer?
    private var currentAudioData: Data? // Store current audio data
    
    func play(_ audioData: Data) {
        stop() // Stop any existing playback
        
        // Store audio data for speaker toggle
        currentAudioData = audioData
        
        do {
            // Routage centralisé: haut-parleur par défaut, écouteur au capteur de
            // proximité. La session est configurée AVANT la création du lecteur.
            VoiceMessageAudioRoute.shared.setPrefersSpeaker(useSpeaker)
            VoiceMessageAudioRoute.shared.beginPlayback()

            audioPlayer = try AVAudioPlayer(data: audioData)
            audioPlayer?.delegate = self
            audioPlayer?.play()
            isPlaying = true
            OshiLog.call.info("▶️ Started playing audio (\(audioData.count) bytes) through \(useSpeaker ? "SPEAKER" : "EARPIECE")")
            
            // Start timer to update current time
            updateTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
                guard let self = self, let player = self.audioPlayer else { return }
                self.currentTime = player.currentTime
            }
        } catch {
            OshiLog.call.info("❌ Failed to play audio: \(error)")
        }
    }
    
    func stop() {
        audioPlayer?.stop()
        isPlaying = false
        updateTimer?.invalidate()
        updateTimer = nil
        currentTime = 0.0
        VoiceMessageAudioRoute.shared.endPlayback()
    }

    func toggleSpeaker() {
        useSpeaker.toggle()
        OshiLog.call.info("🔊 Speaker toggled: \(useSpeaker ? "ON (speaker)" : "OFF (earpiece)")")

        // Bascule À CHAUD. L'ancienne version faisait stop() + play() puis
        // réappliquait `currentTime`: en pratique le message repartait du début
        // ou sautait, parce que `play()` recrée le lecteur et que la position
        // était réécrite sur l'ancien. Changer la route sur la session en cours
        // suffit — le lecteur suit.
        VoiceMessageAudioRoute.shared.setPrefersSpeaker(useSpeaker)
    }
    
    func togglePlayback(_ audioData: Data) {
        if isPlaying {
            stop()
        } else {
            play(audioData)
        }
    }
    
    // AVAudioPlayerDelegate
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        DispatchQueue.main.async {
            self.isPlaying = false
            self.updateTimer?.invalidate()
            self.updateTimer = nil
            self.currentTime = 0.0
            // Sans ça, la surveillance de proximité resterait active après la fin
            // du message et l'écran s'éteindrait dès qu'on approche l'appareil.
            VoiceMessageAudioRoute.shared.endPlayback()
        }
    }
}
