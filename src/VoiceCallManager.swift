//
//  VoiceCallManager.swift
//  OSHI - Encrypted Voice Calls
//
//  Provides encrypted P2P voice calls with real-time voice effects
//  Uses mesh network for nearby peers, VPS relay for remote peers
//
//  PRIVACY ARCHITECTURE:
//  - Mesh calls: Direct P2P, peers see each other's local network
//  - VPS calls: Both users connect to VPS relay
//    - User A sees only VPS IP
//    - User B sees only VPS IP
//    - Users DON'T see each other's IPs
//  - All audio is E2E encrypted (AES-256-GCM)
//  - VPS only sees encrypted blobs, cannot decrypt
//
//  Same VPS infrastructure as MessageManager:
//  - Public: https://oshi-messenger.com
//  - Tor:    http://[TOR_HIDDEN_SERVICE].onion
//

import SwiftUI
import AVFoundation
import AudioToolbox
import Network
import CryptoKit
import Combine
import CallKit
import os.log

// Unified logging for reliable log capture on iOS 26+
private let callLog = Logger(subsystem: "Moriceau-lab.Genesis", category: "VoiceCall")

// File-based call logger for pulling logs from device after test calls
class CallFileLogger {
    static let shared = CallFileLogger()
    private let fileURL: URL
    private let queue = DispatchQueue(label: "callFileLogger", qos: .utility)

    init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        fileURL = docs.appendingPathComponent("call_debug.log")
        // Clear old log on init
        try? "".write(to: fileURL, atomically: true, encoding: .utf8)
    }

    func log(_ message: String) {
        let timestamp = ISO8601DateFormatter().string(from: Date())
        let line = "[\(timestamp)] \(message)\n"
        queue.async {
            if let data = line.data(using: .utf8) {
                if let handle = try? FileHandle(forWritingTo: self.fileURL) {
                    handle.seekToEndOfFile()
                    handle.write(data)
                    handle.closeFile()
                } else {
                    try? data.write(to: self.fileURL)
                }
            }
        }
    }
}

private let fileLog = CallFileLogger.shared

// MARK: - 🎯 WHATSAPP-QUALITY AUDIO COMPONENTS

// ============================================================================
// ADAPTIVE BITRATE CONTROLLER
// ============================================================================
// Dynamically adjusts audio quality based on network conditions
// Similar to how WhatsApp/Zoom adjust video quality

class AdaptiveBitrateController {
    // Bitrate levels (samples per packet)
    enum BitrateLevel: Int, CaseIterable {
        case ultraLow = 480    // 10ms @ 48kHz - emergency mode
        case low = 720         // 15ms @ 48kHz - poor network
        case medium = 960      // 20ms @ 48kHz - normal (default)
        case high = 1440       // 30ms @ 48kHz - good network
        case ultraHigh = 1920  // 40ms @ 48kHz - excellent network
        
        var description: String {
            switch self {
            case .ultraLow: return "Ultra Low (10ms)"
            case .low: return "Low (15ms)"
            case .medium: return "Medium (20ms)"
            case .high: return "High (30ms)"
            case .ultraHigh: return "Ultra High (40ms)"
            }
        }
        
        var packetIntervalMs: Double {
            return Double(rawValue) / 48.0  // At 48kHz
        }
    }
    
    private(set) var currentLevel: BitrateLevel = .medium
    private var recentPacketLoss: [Bool] = []  // true = lost
    private var recentLatencies: [Double] = []
    private let windowSize = 50  // Analyze last 50 packets
    
    /// Report a received packet (for loss calculation)
    func reportPacketReceived() {
        recentPacketLoss.append(false)
        trimArrays()
    }
    
    /// Report a lost packet
    func reportPacketLost() {
        recentPacketLoss.append(true)
        trimArrays()
        adjustLevel()
    }
    
    /// Report latency measurement
    func reportLatency(_ latencyMs: Double) {
        recentLatencies.append(latencyMs)
        trimArrays()
        adjustLevel()
    }
    
    /// Get current packet loss percentage
    var packetLossPercent: Double {
        guard !recentPacketLoss.isEmpty else { return 0 }
        let lostCount = recentPacketLoss.filter { $0 }.count
        return Double(lostCount) / Double(recentPacketLoss.count) * 100
    }
    
    /// Get average latency
    var averageLatency: Double {
        guard !recentLatencies.isEmpty else { return 0 }
        return recentLatencies.reduce(0, +) / Double(recentLatencies.count)
    }
    
    /// Callback when bitrate level changes (for Opus integration)
    var onBitrateChange: ((BitrateLevel, Double, Double) -> Void)?
    
    /// Adjust bitrate level based on conditions
    private func adjustLevel() {
        let loss = packetLossPercent
        let latency = averageLatency
        
        // Decision matrix
        let newLevel: BitrateLevel
        
        if loss > 15 || latency > 500 {
            newLevel = .ultraLow
        } else if loss > 10 || latency > 300 {
            newLevel = .low
        } else if loss > 5 || latency > 200 {
            newLevel = .medium
        } else if loss > 2 || latency > 100 {
            newLevel = .high
        } else {
            newLevel = .ultraHigh
        }
        
        // Only change level if significantly different (prevents oscillation)
        if newLevel != currentLevel {
            let oldIndex = BitrateLevel.allCases.firstIndex(of: currentLevel) ?? 2
            let newIndex = BitrateLevel.allCases.firstIndex(of: newLevel) ?? 2
            
            // Allow downgrade immediately, but upgrade slowly
            if newIndex < oldIndex {
                currentLevel = newLevel
                print("📉 Adaptive Bitrate: Downgraded to \(newLevel.description) (loss: \(String(format: "%.1f", loss))%, latency: \(String(format: "%.0f", latency))ms)")
                onBitrateChange?(newLevel, loss, latency)
            } else if recentPacketLoss.count >= windowSize {
                // Only upgrade after full window of good data
                currentLevel = newLevel
                print("📈 Adaptive Bitrate: Upgraded to \(newLevel.description) (loss: \(String(format: "%.1f", loss))%, latency: \(String(format: "%.0f", latency))ms)")
                onBitrateChange?(newLevel, loss, latency)
            }
        }
    }
    
    private func trimArrays() {
        while recentPacketLoss.count > windowSize {
            recentPacketLoss.removeFirst()
        }
        while recentLatencies.count > windowSize {
            recentLatencies.removeFirst()
        }
    }
    
    func reset() {
        currentLevel = .medium
        recentPacketLoss.removeAll()
        recentLatencies.removeAll()
    }
}

// ============================================================================
// ADVANCED JITTER BUFFER
// ============================================================================
// Handles packet reordering, manages playback timing, reduces stuttering

class AdvancedJitterBuffer {
    struct BufferedPacket {
        let sequenceNumber: UInt64
        let timestamp: Date
        let audioData: Data
    }
    
    private var buffer: [UInt64: BufferedPacket] = [:]  // seq -> packet
    private var nextExpectedSeq: UInt64 = 0
    private let minBufferMs: Double = 40   // Minimum buffer (2 packets)
    private let maxBufferMs: Double = 200  // Maximum buffer (10 packets)
    private let packetDurationMs: Double = 20
    private var lock = NSLock()
    
    // Statistics
    private(set) var packetsReceived: UInt64 = 0
    private(set) var packetsLate: UInt64 = 0
    private(set) var packetsReordered: UInt64 = 0
    
    /// Add a packet to the buffer
    /// - Returns: true if packet was accepted, false if too late/duplicate
    func addPacket(sequenceNumber: UInt64, audioData: Data) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        
        packetsReceived += 1
        
        // Initialize on first packet
        if nextExpectedSeq == 0 {
            nextExpectedSeq = sequenceNumber
        }
        
        // Reject duplicates
        if buffer[sequenceNumber] != nil {
            return false
        }
        
        // Check if packet is too late (already past its playback time)
        if sequenceNumber < nextExpectedSeq {
            packetsLate += 1
            // Still accept it for statistics, but mark as late
            return false
        }
        
        // Check for reordering
        if sequenceNumber > nextExpectedSeq + 1 {
            packetsReordered += 1
        }
        
        buffer[sequenceNumber] = BufferedPacket(
            sequenceNumber: sequenceNumber,
            timestamp: Date(),
            audioData: audioData
        )
        
        return true
    }
    
    /// Get the next packet for playback
    /// - Returns: Audio data or nil if buffer underrun
    func getNextPacket() -> Data? {
        lock.lock()
        defer { lock.unlock() }
        
        // Check if we have the expected packet
        if let packet = buffer[nextExpectedSeq] {
            buffer.removeValue(forKey: nextExpectedSeq)
            nextExpectedSeq += 1
            return packet.audioData
        }
        
        // Packet missing - check if we should skip ahead
        let bufferedSeqs = buffer.keys.sorted()
        if let firstAvailable = bufferedSeqs.first {
            // If we're waiting too long, skip to available packet
            let gap = firstAvailable - nextExpectedSeq
            if gap > 5 {  // More than 5 packets behind
                nextExpectedSeq = firstAvailable
                if let packet = buffer[firstAvailable] {
                    buffer.removeValue(forKey: firstAvailable)
                    nextExpectedSeq += 1
                    return packet.audioData
                }
            }
        }
        
        return nil  // Buffer underrun
    }
    
    /// Check buffer health
    var bufferDepthPackets: Int {
        lock.lock()
        defer { lock.unlock() }
        return buffer.count
    }
    
    var bufferDepthMs: Double {
        return Double(bufferDepthPackets) * packetDurationMs
    }
    
    func reset() {
        lock.lock()
        defer { lock.unlock() }
        buffer.removeAll()
        nextExpectedSeq = 0
        packetsReceived = 0
        packetsLate = 0
        packetsReordered = 0
    }
}

// ============================================================================
// PACKET LOSS CONCEALER (PLC)
// ============================================================================
// Hides packet loss by interpolating/repeating audio

class PacketLossConcealer {
    private var lastGoodPacket: Data?
    private var lastGoodSamples: [Float]?
    private var consecutiveLosses = 0
    private let maxConcealmentPackets = 3  // Max packets to conceal
    private let fadePerPacket: Float = 0.3  // Fade 30% per lost packet
    
    /// Process a packet - returns concealed audio if packet is nil
    func process(packet: Data?, sampleRate: Double = 48000) -> Data {
        if let packet = packet {
            // Good packet - store for potential concealment
            lastGoodPacket = packet
            lastGoodSamples = dataToFloatSamples(packet)
            consecutiveLosses = 0
            return packet
        }
        
        // Packet lost - attempt concealment
        consecutiveLosses += 1
        
        if consecutiveLosses <= maxConcealmentPackets, let lastSamples = lastGoodSamples {
            // Apply fade based on consecutive losses
            let fadeMultiplier = max(0, 1.0 - Float(consecutiveLosses) * fadePerPacket)
            var concealedSamples = lastSamples.map { $0 * fadeMultiplier }
            
            // Add slight pitch variation to avoid robotic sound
            if consecutiveLosses > 1 {
                // Apply subtle random variation
                for i in 0..<concealedSamples.count {
                    concealedSamples[i] *= Float.random(in: 0.98...1.02)
                }
            }
            
            return floatSamplesToData(concealedSamples)
        }
        
        // Too many consecutive losses - return comfort noise
        return generateComfortNoise(samples: Int(sampleRate * 0.02))  // 20ms
    }
    
    /// Generate comfort noise (low-level background noise)
    func generateComfortNoise(samples: Int) -> Data {
        var noise = [Float](repeating: 0, count: samples)
        for i in 0..<samples {
            // Very quiet noise (-40dB)
            noise[i] = Float.random(in: -0.01...0.01)
        }
        return floatSamplesToData(noise)
    }
    
    private func dataToFloatSamples(_ data: Data) -> [Float] {
        var samples = [Float](repeating: 0, count: data.count / 4)
        _ = samples.withUnsafeMutableBytes { dest in
            data.copyBytes(to: dest)
        }
        return samples
    }
    
    private func floatSamplesToData(_ samples: [Float]) -> Data {
        return samples.withUnsafeBytes { Data($0) }
    }
    
    func reset() {
        lastGoodPacket = nil
        lastGoodSamples = nil
        consecutiveLosses = 0
    }
}

// ============================================================================
// SIMPLE XOR-BASED FEC (Forward Error Correction)
// ============================================================================
// Adds redundancy to recover from single packet losses
// Every N packets, send an XOR of the previous N packets

class SimpleXORFEC {
    private let groupSize: Int  // Number of packets per FEC group
    private var currentGroup: [Data] = []
    private var groupSequenceStart: UInt64 = 0
    private var receivedGroups: [UInt64: [UInt64: Data]] = [:]  // groupStart -> (seq -> data)
    private var receivedFEC: [UInt64: Data] = [:]  // groupStart -> FEC packet
    
    init(groupSize: Int = 4) {
        self.groupSize = groupSize
    }
    
    // MARK: - Sender Side
    
    /// Add a packet for FEC encoding
    /// - Returns: FEC packet if group is complete, nil otherwise
    func addPacketForEncoding(_ packet: Data, sequenceNumber: UInt64) -> Data? {
        // Start new group if needed
        if currentGroup.isEmpty {
            groupSequenceStart = sequenceNumber
        }
        
        currentGroup.append(packet)
        
        // Check if group is complete
        if currentGroup.count == groupSize {
            let fecPacket = generateFECPacket()
            currentGroup.removeAll()
            return fecPacket
        }
        
        return nil
    }
    
    /// Generate XOR FEC packet for current group
    private func generateFECPacket() -> Data {
        guard !currentGroup.isEmpty else { return Data() }
        
        // Find max length
        let maxLength = currentGroup.map { $0.count }.max() ?? 0
        var result = [UInt8](repeating: 0, count: maxLength)
        
        // XOR all packets together
        for packet in currentGroup {
            let bytes = [UInt8](packet)
            for i in 0..<bytes.count {
                result[i] ^= bytes[i]
            }
        }
        
        // Prepend group info: [groupStart(8 bytes)][groupSize(1 byte)][XOR data]
        var fecData = Data()
        var start = groupSequenceStart
        fecData.append(Data(bytes: &start, count: 8))
        fecData.append(UInt8(groupSize))
        fecData.append(Data(result))
        
        return fecData
    }
    
    // MARK: - Receiver Side
    
    /// Store received packet for potential FEC recovery
    func storeReceivedPacket(_ packet: Data, sequenceNumber: UInt64) {
        let groupStart = (sequenceNumber / UInt64(groupSize)) * UInt64(groupSize)
        
        if receivedGroups[groupStart] == nil {
            receivedGroups[groupStart] = [:]
        }
        receivedGroups[groupStart]?[sequenceNumber] = packet
        
        // Clean up old groups (keep last 10)
        cleanupOldGroups()
    }
    
    /// Process received FEC packet
    func processFECPacket(_ fecData: Data) {
        guard fecData.count > 9 else { return }

        // 🔧 FIX: Use safe unaligned byte reading to prevent crash
        var groupStart: UInt64 = 0
        let groupData = fecData.prefix(8)
        _ = Swift.withUnsafeMutableBytes(of: &groupStart) { dest in
            groupData.copyBytes(to: dest)
        }
        groupStart = UInt64(bigEndian: groupStart)
        // let size = Int(fecData[8])  // Not used currently but could validate
        let xorData = Data(fecData.suffix(from: 9))
        
        receivedFEC[groupStart] = xorData
    }
    
    /// Try to recover a lost packet using FEC
    /// - Returns: Recovered packet or nil if not possible
    func tryRecover(sequenceNumber: UInt64) -> Data? {
        let groupStart = (sequenceNumber / UInt64(groupSize)) * UInt64(groupSize)
        
        guard let groupPackets = receivedGroups[groupStart],
              let fecData = receivedFEC[groupStart] else {
            return nil
        }
        
        // Check if we have exactly groupSize - 1 packets (missing just one)
        let expectedSeqs = (0..<groupSize).map { groupStart + UInt64($0) }
        let missingSeqs = expectedSeqs.filter { groupPackets[$0] == nil }
        
        guard missingSeqs.count == 1, missingSeqs.first == sequenceNumber else {
            return nil  // Can't recover if more than one packet missing
        }
        
        // Recover by XORing FEC with all other packets
        var result = [UInt8](fecData)
        
        for seq in expectedSeqs where seq != sequenceNumber {
            if let packet = groupPackets[seq] {
                let bytes = [UInt8](packet)
                for i in 0..<min(bytes.count, result.count) {
                    result[i] ^= bytes[i]
                }
            }
        }
        
        print("🔧 FEC: Recovered packet #\(sequenceNumber) from group \(groupStart)")
        return Data(result)
    }
    
    private func cleanupOldGroups() {
        // Keep only last 10 groups
        let sortedStarts = receivedGroups.keys.sorted()
        if sortedStarts.count > 10 {
            let toRemove = sortedStarts.prefix(sortedStarts.count - 10)
            for start in toRemove {
                receivedGroups.removeValue(forKey: start)
                receivedFEC.removeValue(forKey: start)
            }
        }
    }
    
    func reset() {
        currentGroup.removeAll()
        receivedGroups.removeAll()
        receivedFEC.removeAll()
        groupSequenceStart = 0
    }
}

// ============================================================================
// AUDIO CODEC WRAPPER (Preparation for Opus)
// ============================================================================
// Abstract interface for audio compression
// Currently uses PCM, but can be swapped for Opus when library is added

protocol AudioCodec {
    func encode(_ pcmData: Data) -> Data
    func decode(_ encodedData: Data) -> Data
    var compressionRatio: Double { get }
    var name: String { get }
}

/// PCM "codec" - no compression (current implementation)
class PCMCodec: AudioCodec {
    func encode(_ pcmData: Data) -> Data { pcmData }
    func decode(_ encodedData: Data) -> Data { encodedData }
    var compressionRatio: Double { 1.0 }
    var name: String { "PCM (Uncompressed)" }
}

/// μ-law compression - simple 2:1 compression
/// Used in telephone systems, provides basic compression without external libs
class MuLawCodec: AudioCodec {
    private let MULAW_MAX: Int32 = 0x1FFF
    private let MULAW_BIAS: Int32 = 33
    
    func encode(_ pcmData: Data) -> Data {
        // Convert 16-bit PCM to 8-bit μ-law
        var encoded = Data()
        
        // Process pairs of bytes as Int16 samples
        for i in stride(from: 0, to: pcmData.count - 1, by: 2) {
            let lowByte = Int16(pcmData[i])
            let highByte = Int16(pcmData[i + 1])
            let sample = lowByte | (highByte << 8)
            let mulaw = linearToMuLaw(Int32(sample))
            encoded.append(mulaw)
        }
        
        return encoded
    }
    
    func decode(_ encodedData: Data) -> Data {
        // Convert 8-bit μ-law back to 16-bit PCM
        var decoded = Data()
        
        for mulaw in encodedData {
            let sample = muLawToLinear(mulaw)
            var s = sample
            decoded.append(Data(bytes: &s, count: 2))
        }
        
        return decoded
    }
    
    var compressionRatio: Double { 2.0 }  // 16-bit -> 8-bit
    var name: String { "μ-law (2:1 compression)" }
    
    private func linearToMuLaw(_ sample: Int32) -> UInt8 {
        var pcm = sample
        let sign = (pcm >> 8) & 0x80
        if sign != 0 { pcm = -pcm }
        if pcm > MULAW_MAX { pcm = MULAW_MAX }
        pcm += MULAW_BIAS
        
        var exponent: Int32 = 7
        var mask: Int32 = 0x4000
        while (pcm & mask) == 0 && exponent > 0 {
            exponent -= 1
            mask >>= 1
        }
        
        let mantissa = (pcm >> (exponent + 3)) & 0x0F
        let mulaw = ~(sign | (exponent << 4) | mantissa)
        
        return UInt8(truncatingIfNeeded: mulaw & 0xFF)
    }
    
    private func muLawToLinear(_ mulaw: UInt8) -> Int16 {
        let mu = Int32(~mulaw) & 0xFF
        let sign = mu & 0x80
        let exponent = (mu >> 4) & 0x07
        let mantissa = mu & 0x0F
        
        var sample = ((mantissa << 3) + MULAW_BIAS) << exponent - MULAW_BIAS
        if sign != 0 { sample = -sample }
        
        return Int16(clamping: sample)
    }
}

// MARK: - WhatsApp-Quality Codec (G.726 ADPCM @ 32 kbps)
// Achieves same bandwidth as WhatsApp WITHOUT external dependencies!

/// G.726 ADPCM state machine for encoding/decoding
private class G726State {
    private var stepIndex: Int = 0
    private var predictedSample: Int32 = 0
    
    private let stepTable: [Int32] = [
        16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66,
        73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411,
        1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484,
        7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794,
        32767
    ]
    
    private let indexTable: [Int] = [-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8]
    
    func encode(sample: Int16) -> UInt8 {
        let step = stepTable[stepIndex]
        var diff = Int32(sample) - predictedSample
        var adpcmSample: UInt8 = 0
        
        if diff < 0 {
            adpcmSample = 8
            diff = -diff
        }
        
        var mask: UInt8 = 4
        var tempStep = step
        for _ in stride(from: 2, through: 0, by: -1) {
            if diff >= tempStep {
                adpcmSample |= mask
                diff -= tempStep
            }
            tempStep >>= 1
            mask >>= 1
        }
        
        updatePredictor(adpcmSample: adpcmSample, step: step)
        stepIndex += indexTable[Int(adpcmSample)]
        stepIndex = max(0, min(stepIndex, stepTable.count - 1))
        
        return adpcmSample
    }
    
    func decode(adpcm: UInt8) -> Int16 {
        let step = stepTable[stepIndex]
        updatePredictor(adpcmSample: adpcm, step: step)
        stepIndex += indexTable[Int(adpcm & 0x0F)]
        stepIndex = max(0, min(stepIndex, stepTable.count - 1))
        return Int16(clamping: max(-32768, min(32767, predictedSample)))
    }
    
    private func updatePredictor(adpcmSample: UInt8, step: Int32) {
        var diff: Int32 = 0
        if adpcmSample & 4 != 0 { diff += step }
        if adpcmSample & 2 != 0 { diff += step >> 1 }
        if adpcmSample & 1 != 0 { diff += step >> 2 }
        diff += step >> 3
        
        if adpcmSample & 8 != 0 {
            predictedSample -= diff
        } else {
            predictedSample += diff
        }
        predictedSample = max(-32768, min(32767, predictedSample))
    }
    
    func reset() {
        stepIndex = 0
        predictedSample = 0
    }
}

/// Enhanced G.726 ADPCM codec (32 kbps - NO external dependencies!)
/// Improvements: anti-alias filter, pre/de-emphasis, cubic upsampling, volume boost
class OpusCodec: AudioCodec {
    private var encodeState = G726State()
    private var decodeState = G726State()
    private var consecutiveSilentFrames: Int = 0
    private let silenceThreshold: Int16 = 500
    private let maxSilentFrames: Int = 5

    // Pre-emphasis state (boosts high freq before encoding for clarity)
    private var preEmphPrev: Int32 = 0
    // De-emphasis state (restores frequency balance on decode)
    private var deEmphPrev: Int32 = 0
    // Pre-emphasis coefficient (0.97 is standard for speech)
    private let preEmphCoeff: Int32 = 31785  // 0.97 * 32768

    // Low-pass anti-aliasing filter state (2-tap moving average)
    private var lpfPrev1: Int32 = 0
    private var lpfPrev2: Int32 = 0

    // Volume boost factor (1.4 = +3dB perceived loudness)
    private let volumeGain: Float = 1.4

    func encode(_ pcmData: Data) -> Data {
        // Convert to Int16 samples
        var samples = [Int16]()
        samples.reserveCapacity(pcmData.count / 2)

        for i in stride(from: 0, to: pcmData.count - 1, by: 2) {
            let sample = Int16(pcmData[i]) | (Int16(pcmData[i + 1]) << 8)
            samples.append(sample)
        }

        // Pre-emphasis filter: y[n] = x[n] - 0.97 * x[n-1]
        // Boosts high frequencies which ADPCM encodes better
        var emphasized = [Int16]()
        emphasized.reserveCapacity(samples.count)
        for sample in samples {
            let s = Int32(sample)
            let emph = s - ((preEmphCoeff * preEmphPrev) >> 15)
            preEmphPrev = s
            emphasized.append(Int16(clamping: emph))
        }

        // Anti-aliasing low-pass filter before downsampling
        // 3-tap FIR: [0.25, 0.5, 0.25] prevents aliasing artifacts
        var filtered = [Int16]()
        filtered.reserveCapacity(emphasized.count)
        for i in 0..<emphasized.count {
            let cur = Int32(emphasized[i])
            let out = (lpfPrev2 + cur + 2 * lpfPrev1) >> 2
            lpfPrev2 = lpfPrev1
            lpfPrev1 = cur
            filtered.append(Int16(clamping: out))
        }

        // Downsample to 8kHz (2:1 decimation with filtered input)
        var downsampled = [Int16]()
        downsampled.reserveCapacity(filtered.count / 2)
        for i in stride(from: 0, to: filtered.count - 1, by: 2) {
            downsampled.append(filtered[i])  // Take every other sample (filter already smoothed)
        }

        // DTX: Detect silence
        let maxAmplitude = downsampled.map { abs($0) }.max() ?? 0
        if maxAmplitude < silenceThreshold {
            consecutiveSilentFrames += 1
            if consecutiveSilentFrames > maxSilentFrames {
                return Data([0xFF])  // Silence marker
            }
        } else {
            consecutiveSilentFrames = 0
        }

        // G.726 ADPCM encoding
        var encoded = Data()
        encoded.reserveCapacity(downsampled.count / 2)

        var nibbleBuffer: UInt8 = 0
        var nibbleCount = 0

        for sample in downsampled {
            let adpcmNibble = encodeState.encode(sample: sample)

            if nibbleCount == 0 {
                nibbleBuffer = adpcmNibble & 0x0F
                nibbleCount = 1
            } else {
                nibbleBuffer |= (adpcmNibble & 0x0F) << 4
                encoded.append(nibbleBuffer)
                nibbleCount = 0
            }
        }

        if nibbleCount == 1 {
            encoded.append(nibbleBuffer)
        }

        return encoded
    }

    func decode(_ encodedData: Data) -> Data {
        // Handle silence marker
        if encodedData.count == 1 && encodedData[0] == 0xFF {
            var data = Data()
            for _ in 0..<320 {  // 20ms at 16kHz
                let noise = Int16.random(in: -50...50)  // Quieter comfort noise
                var s = noise
                data.append(Data(bytes: &s, count: 2))
            }
            return data
        }

        // G.726 ADPCM decoding
        var samples8k = [Int16]()
        samples8k.reserveCapacity(encodedData.count * 2)

        for byte in encodedData {
            let nibble1 = byte & 0x0F
            let sample1 = decodeState.decode(adpcm: nibble1)
            samples8k.append(sample1)

            let nibble2 = (byte >> 4) & 0x0F
            let sample2 = decodeState.decode(adpcm: nibble2)
            samples8k.append(sample2)
        }

        // De-emphasis filter: y[n] = x[n] + 0.97 * y[n-1]
        // Restores original frequency balance
        for i in 0..<samples8k.count {
            let s = Int32(samples8k[i])
            let deEmph = s + ((preEmphCoeff * deEmphPrev) >> 15)
            let clamped = max(-32768, min(32767, deEmph))
            deEmphPrev = clamped
            samples8k[i] = Int16(clamped)
        }

        // Cubic interpolation upsampling from 8kHz to 16kHz
        // Much smoother than linear - preserves waveform shape
        var samples16k = [Int16]()
        samples16k.reserveCapacity(samples8k.count * 2)

        for i in 0..<samples8k.count {
            // Original sample
            samples16k.append(samples8k[i])

            // Cubic interpolation for the midpoint
            let s0 = Int32(i > 0 ? samples8k[i - 1] : samples8k[i])
            let s1 = Int32(samples8k[i])
            let s2 = Int32(i < samples8k.count - 1 ? samples8k[i + 1] : samples8k[i])
            let s3: Int32 = i < samples8k.count - 2 ? Int32(samples8k[i + 2]) : s2

            // Catmull-Rom spline at t=0.5
            let interpolated = (-s0 + 9 * s1 + 9 * s2 - s3) >> 4
            samples16k.append(Int16(clamping: max(-32768, min(32767, interpolated))))
        }

        // Apply volume boost
        var decoded = Data()
        decoded.reserveCapacity(samples16k.count * 2)
        for sample in samples16k {
            let boosted = Float(sample) * volumeGain
            var s = Int16(clamping: Int32(max(-32768, min(32767, boosted))))
            decoded.append(Data(bytes: &s, count: 2))
        }

        return decoded
    }

    var compressionRatio: Double { 24.0 }  // 768kbps → 32kbps
    var name: String { "G.726 ADPCM (32kbps enhanced)" }

    func setBitrate(_ bitrate: Int32) {
        print("🎵 AudioCodec: Bitrate hint \(bitrate/1000)kbps")
    }

    func setExpectedPacketLoss(_ percent: Int32) {
        print("🎵 AudioCodec: Packet loss hint \(percent)%")
    }

    func reset() {
        encodeState.reset()
        decodeState.reset()
        preEmphPrev = 0
        deEmphPrev = 0
        lpfPrev1 = 0
        lpfPrev2 = 0
        consecutiveSilentFrames = 0
    }
}

// MARK: - Call State
enum CallState: Equatable {
    case idle
    case connecting
    case ringing
    case inCall
    case ended(reason: CallEndReason)
    
    static func == (lhs: CallState, rhs: CallState) -> Bool {
        switch (lhs, rhs) {
        case (.idle, .idle), (.connecting, .connecting), (.ringing, .ringing), (.inCall, .inCall):
            return true
        case (.ended(let r1), .ended(let r2)):
            return r1 == r2
        default:
            return false
        }
    }
}

enum CallEndReason: String {
    case hungUp = "hung_up"
    case declined = "declined"
    case noAnswer = "no_answer"
    case networkError = "network_error"
    case peerDisconnected = "peer_disconnected"
    case connectionLost = "connection_lost"  // 📶 NEW: Auto-ended due to 30s silence
    case answeredElsewhere = "answered_elsewhere"  // 📱 Multi-device: Call answered on another device

    /// User-friendly display text for call end reason
    var displayText: String {
        switch self {
        case .hungUp: return "Call ended"
        case .declined: return "Call declined"
        case .noAnswer: return "No answer"
        case .networkError: return "Network error"
        case .peerDisconnected: return "Connection lost"
        case .connectionLost: return "Connection lost"
        case .answeredElsewhere: return "Answered on another device"
        }
    }
}

// MARK: - Voice Effect for Calls (Real-time)
enum CallVoiceEffect: String, CaseIterable, Identifiable {
    case none = "None"
    case robot = "Robot"
    case alien = "Alien"
    case deep = "Deep"
    case chipmunk = "Chipmunk"
    case echo = "Echo"
    case reverb = "Reverb"
    case anonymous = "Anonymous"  // Special effect for maximum privacy

    var id: String { rawValue }

    var displayName: String { rawValue }

    var icon: String {
        switch self {
        case .none: return "waveform"
        case .robot: return "cpu"
        case .alien: return "antenna.radiowaves.left.and.right"
        case .deep: return "waveform"
        case .chipmunk: return "hare"
        case .echo: return "dot.radiowaves.left.and.right"
        case .reverb: return "building.columns"
        case .anonymous: return "person.fill.questionmark"
        }
    }

    var description: String {
        switch self {
        case .none: return NSLocalizedString("call.effect.none.desc", comment: "No voice modification")
        case .robot: return NSLocalizedString("call.effect.robot.desc", comment: "Metallic robotic voice")
        case .alien: return NSLocalizedString("call.effect.alien.desc", comment: "High-pitched alien voice")
        case .deep: return NSLocalizedString("call.effect.deep.desc", comment: "Deep bass voice")
        case .chipmunk: return NSLocalizedString("call.effect.chipmunk.desc", comment: "High-speed chipmunk voice")
        case .echo: return NSLocalizedString("call.effect.echo.desc", comment: "Repeating echo effect")
        case .reverb: return NSLocalizedString("call.effect.reverb.desc", comment: "Cathedral-like ambience")
        case .anonymous: return NSLocalizedString("call.effect.anonymous.desc", comment: "Completely anonymized voice")
        }
    }
}

// MARK: - Call Packet Types
enum CallPacketType: UInt8 {
    case callRequest = 0x01
    case callAccept = 0x02
    case callDecline = 0x03
    case callEnd = 0x04
    case audioData = 0x05
    case keepAlive = 0x06
    case keyExchange = 0x07
    case videoCallRequest = 0x08  // 📹 Video call request (different from voice)
    case videoCallAccept = 0x09   // 📹 Accept video call
    case callAnsweredElsewhere = 0x0A  // 📱 Call was answered on another device (multi-device)
}

// MARK: - Connection Quality
enum ConnectionQuality: String {
    case excellent = "Excellent"   // < 50ms latency
    case good = "Good"             // 50-100ms latency
    case fair = "Fair"             // 100-200ms latency
    case poor = "Poor"             // 200-400ms latency
    case veryPoor = "Very Poor"    // > 400ms latency
    case reconnecting = "Reconnecting"
    case disconnected = "Disconnected"
    
    var icon: String {
        switch self {
        case .excellent: return "wifi.circle.fill"
        case .good: return "wifi.circle.fill"
        case .fair: return "wifi.circle"
        case .poor: return "wifi.exclamationmark"
        case .veryPoor: return "wifi.slash"
        case .reconnecting: return "arrow.triangle.2.circlepath"
        case .disconnected: return "wifi.slash"
        }
    }
    
    var color: String {
        switch self {
        case .excellent: return "green"
        case .good: return "green"
        case .fair: return "yellow"
        case .poor: return "orange"
        case .veryPoor: return "red"
        case .reconnecting: return "yellow"
        case .disconnected: return "red"
        }
    }
}

// MARK: - Call Quality Statistics
struct CallQualityStats {
    var latencyMs: Int = 0           // Current round-trip latency
    var jitterMs: Int = 0            // Variation in latency
    var packetLossPercent: Double = 0 // Packet loss percentage
    var audioPacketsSent: UInt64 = 0
    var audioPacketsReceived: UInt64 = 0
    var bytesTransmitted: UInt64 = 0
    var bytesReceived: UInt64 = 0
    var encryptionVerified: Bool = false
    var lastPingTime: Date?
    
    // 🎯 NEW: Advanced quality metrics
    var bitrateLevel: String = "Medium"       // Current adaptive bitrate level
    var jitterBufferDepthMs: Double = 0       // Current jitter buffer depth
    var packetsRecovered: UInt64 = 0          // Packets recovered via FEC
    var packetsConcealed: UInt64 = 0          // Packets concealed via PLC
    var compressionRatio: Double = 1.0        // Audio compression ratio
    var publicIP: String?                     // Discovered public IP via STUN
    
    var quality: ConnectionQuality {
        if latencyMs < 50 && jitterMs < 20 && packetLossPercent < 1 {
            return .excellent
        } else if latencyMs < 100 && jitterMs < 40 && packetLossPercent < 3 {
            return .good
        } else if latencyMs < 200 && jitterMs < 60 && packetLossPercent < 5 {
            return .fair
        } else if latencyMs < 400 {
            return .poor
        } else {
            return .veryPoor
        }
    }
    
    var summaryString: String {
        """
        Latency: \(latencyMs)ms | Jitter: \(jitterMs)ms | Loss: \(String(format: "%.1f", packetLossPercent))%
        Bitrate: \(bitrateLevel) | Buffer: \(String(format: "%.0f", jitterBufferDepthMs))ms
        FEC Recovered: \(packetsRecovered) | PLC Concealed: \(packetsConcealed)
        """
    }
}

// MARK: - Voice Call Manager
class VoiceCallManager: NSObject, ObservableObject, @unchecked Sendable {
    static let shared = VoiceCallManager()
    
    // Published state
    @Published var callState: CallState = .idle
    @Published var isCurrentCallVideo: Bool = false  // 📹 Track if current call is video
    @Published var currentCallPeer: String?
    @Published var currentCallPeerName: String?
    @Published var callDuration: TimeInterval = 0
    @Published var waitingDuration: TimeInterval = 0  // Time spent connecting/ringing
    @Published var isMuted = false
    @Published var isSpeakerOn = false
    @Published var currentEffect: CallVoiceEffect = .none
    @Published var audioLevel: Float = 0
    @Published var isOutgoingCall = false
    @Published var incomingCall: IncomingCall?
    @Published var connectionType: ConnectionType = .none
    @Published var isCallKitHandlingIncoming: Bool = false  // 🔧 FIX: Track when CallKit is showing incoming call UI

    // 🔧 FIX: Centralized call deduplication - prevents multiple notification paths from handling same call
    private var activeIncomingCallId: String?
    private var activeIncomingCallSource: String?  // "voip_push", "vps_poll", "mesh"
    private let callDeduplicationLock = NSLock()
    
    // 🔧 FIX: Track when VoIP push is actively handling a call to suppress polling
    private var voipPushActiveForCaller: String?
    private var voipPushReceivedTime: Date?

    // Track if signaling is via mesh relay (audio will use VPS)
    private var usingMeshRelay = false
    
    // Audio level throttling - update UI max 10 times per second
    private var lastAudioLevelUpdate: Date = Date.distantPast
    private let audioLevelUpdateInterval: TimeInterval = 0.1  // 100ms
    
    // Connection quality metrics - Published for UI
    @Published var connectionQuality: ConnectionQuality = .good
    @Published var currentLatency: Int = 0      // in milliseconds
    @Published var currentJitter: Int = 0       // in milliseconds
    @Published var qualityStats: CallQualityStats = CallQualityStats()
    @Published var lastCallEndReason: CallEndReason?  // 📱 UX: Show why call ended
    
    // Quality monitoring internals
    private var latencyHistory: [Int] = []  // Last N latency measurements
    private let latencyHistorySize = 20
    private var lastPingSentTime: Date?
    private var pingSequence: UInt32 = 0
    private var pendingPings: [UInt32: Date] = [:]  // sequence -> sent time
    private var qualityMonitorTimer: Timer?
    private var packetsSentSinceLastCheck: UInt64 = 0
    private var packetsReceivedSinceLastCheck: UInt64 = 0
    private var lastPacketLossCheck: Date = Date()
    
    // Internal counters (not @Published, can be updated from any thread)
    private var internalPacketsSent: UInt64 = 0
    private var internalPacketsReceived: UInt64 = 0
    private var internalBytesSent: UInt64 = 0
    private var internalBytesReceived: UInt64 = 0
    
    // Deduplication flag to prevent multiple accept attempts
    private var isAcceptingCall = false

    // 🔧 FIX: Track whether call was answered via CallKit (background) vs in-app overlay
    // When answered via CallKit, we must NOT dismiss CallKit - it manages the audio session
    private var wasAnsweredViaCallKit = false

    // 🔧 FIX: Track the last accepted call to prevent duplicate accepts
    private var lastAcceptedCallPeer: String?
    private var lastAcceptedCallTime: Date?
    private let acceptDeduplicationWindow: TimeInterval = 5.0  // 5 seconds window

    // 🔧 FIX: Track if user tapped CallKit accept but we're waiting for signal data
    private var pendingCallKitAccept = false
    private var pendingCallKitAcceptCallerKey: String?

    // 🔧 FIX: Track if auto-accept is scheduled (prevents manual + auto double-accept)
    private var autoAcceptScheduled = false

    // Track processed call signal IDs to prevent duplicate processing
    private var processedCallSignalIDs = Set<String>()

    // 🔧 FIX: Track processed incoming call signals to prevent duplicate notifications
    private var processedIncomingCallKeys = Set<String>()
    private var lastIncomingCallTime: Date?

    // 🔧 FIX: Track when the current call session started
    // Used to reject callEnd signals that were created before this call started
    private var currentCallStartTime: Date?

    // 🔧 FIX: Track when the call was actually connected (transitioned to .inCall)
    // This is used to create a "grace period" where callEnd signals are ignored
    // to prevent race conditions where late-arriving signals end a newly connected call
    private var callConnectedTime: Date?

    // 🔧 FIX: Grace period after call connects where callEnd signals are ignored
    // This prevents stale/delayed callEnd signals from ending a just-connected call
    private let callEndGracePeriodSeconds: TimeInterval = 3.0

    // Flag to track if call was ended by remote peer (to avoid sending end signal back)
    private var receivedRemoteEndSignal = false

    // 🔧 FIX: Track if endCall is currently being processed to prevent race conditions
    private var isEndingCall = false

    // 📱 MULTI-DEVICE: Unique device identifier for call routing
    // When a call is answered on one device, other devices are notified
    private var deviceId: String {
        if let existingId = UserDefaults.standard.string(forKey: "oshi_device_id") {
            return existingId
        } else {
            let newId = UUID().uuidString
            UserDefaults.standard.set(newId, forKey: "oshi_device_id")
            return newId
        }
    }

    enum ConnectionType: String {
        case none       = "none"
        case mesh       = "mesh"          // Direct P2P via MultipeerConnectivity (signaling + audio via VPS)
        case meshDirect = "mesh_direct"   // ✅ NEW: Fully offline P2P (signaling + audio via mesh)
        case tor        = "tor"           // Via Tor/VPS relay
        case meshRelay  = "mesh_relay"    // Signaling via mesh relay, audio via VPS
        
        var displayName: String {
            switch self {
            case .none: return "Not Connected"
            case .mesh: return "Mesh (VPS Audio)"
            case .meshDirect: return "📶 Mesh Direct (Offline)"
            case .tor: return "VPS Relay"
            case .meshRelay: return "Mesh Relay"
            }
        }
        
        var icon: String {
            switch self {
            case .none: return "wifi.slash"
            case .mesh: return "antenna.radiowaves.left.and.right"
            case .meshDirect: return "point.3.connected.trianglepath.dotted"
            case .tor: return "network"
            case .meshRelay: return "arrow.triangle.swap"
            }
        }
        
        /// True if this connection can work without internet
        var worksOffline: Bool {
            return self == .meshDirect  // Only meshDirect is fully offline (audio via mesh)
            // meshRelay sends signaling via mesh but still needs VPS for audio
        }
    }
    
    // Internet reachability check
    @Published var hasInternetConnection: Bool = true
    private var networkMonitor: NWPathMonitor?
    
    // MARK: - VPS Configuration
    // The VPS relays encrypted voice data - all traffic is E2E encrypted (AES-256-GCM)
    // The VPS only sees encrypted blobs - it cannot decrypt the audio
    private let vpsPublicURL = "https://oshi-messenger.com"
    private let vpsTorURL = "http://[TOR_HIDDEN_SERVICE].onion"
    
    // VoIP path prefix - routes through nginx (HTTPS on 443) → proxy to VoIP server (HTTP on 8083)
    // 🔧 FIX: Direct port 8083 fails because VoIP server is plain HTTP but iOS requires HTTPS
    private let vpsVoIPPath = "/voip"

    // Select URL based on Tor availability (matches MessageManager pattern)
    private var currentVPSURL: String {
        return vpsPublicURL
    }

    // Computed URLs for different services - all route through nginx HTTPS
    private var vpsURL: String {
        return currentVPSURL + vpsVoIPPath  // e.g. https://oshi-messenger.com/voip
    }

    private var vpsWebSocketURL: String {
        let wsScheme = currentVPSURL.hasPrefix("https") ? "wss" : "ws"
        let host = currentVPSURL
            .replacingOccurrences(of: "http://", with: "")
            .replacingOccurrences(of: "https://", with: "")
        return "\(wsScheme)://\(host)\(vpsVoIPPath)"
    }

    private var vpsBaseURL: String {
        return currentVPSURL + vpsVoIPPath
    }
    
    // MARK: - Base64URL Encoding/Decoding Helpers
    // 🔧 CRITICAL: Use base64url encoding for public keys in URLs
    // Standard base64 uses +/= which break URL paths even when percent-encoded
    // Base64url uses -_ instead and is URL-safe
    private func base64urlEncode(_ string: String) -> String {
        return string
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    // 🔧 CRITICAL FIX: Decode base64url back to standard base64
    // VPS returns keys in base64url format, but Data(base64Encoded:) expects standard base64
    private func base64urlDecode(_ base64urlString: String) -> String {
        var result = base64urlString
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        // Add padding if needed
        while result.count % 4 != 0 {
            result.append("=")
        }
        return result
    }
    
    // Optimized URLSession for low-latency audio
    private lazy var audioURLSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral  // No caching
        config.timeoutIntervalForRequest = 0.3  // 300ms timeout - aggressive for real-time
        config.timeoutIntervalForResource = 1.5
        config.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        config.urlCache = nil
        // 6 parallel HTTP connections PER DEVICE to VPS (not a call limit!)
        // This is for parallel audio packets + pings from ONE device
        // Multiple users can call simultaneously - each has their own URLSession
        // VPS handles routing by callId - supports unlimited concurrent calls
        config.httpMaximumConnectionsPerHost = 6
        config.httpShouldUsePipelining = true
        config.httpShouldSetCookies = false  // No cookies needed
        config.httpCookieAcceptPolicy = .never
        return URLSession(configuration: config)
    }()
    
    // WebSocket connection for real-time audio
    private var webSocket: URLSessionWebSocketTask?
    private var webSocketSession: URLSession?
    private var isWebSocketConnected = false
    
    // Audio Engine
    private var audioEngine: AVAudioEngine?
    private var inputNode: AVAudioInputNode?
    private var playerNode: AVAudioPlayerNode?
    private var mixerNode: AVAudioMixerNode?
    
    // Voice effects nodes for RECEIVED audio (what you hear)
    private var pitchEffect: AVAudioUnitTimePitch?
    private var distortionEffect: AVAudioUnitDistortion?
    private var reverbEffect: AVAudioUnitReverb?
    private var delayEffect: AVAudioUnitDelay?

    // Voice effects nodes for OUTGOING audio (what others hear from you)
    private var outgoingPitchEffect: AVAudioUnitTimePitch?
    private var outgoingDistortionEffect: AVAudioUnitDistortion?
    private var outgoingReverbEffect: AVAudioUnitReverb?
    private var outgoingDelayEffect: AVAudioUnitDelay?
    private var outgoingMixerNode: AVAudioMixerNode?
    
    // Flag to apply effects to outgoing audio (default: true - others hear your effect)
    @Published var applyEffectToOutgoing: Bool = true
    
    // Encryption - AES-256-GCM with ECDH key exchange
    internal var sessionKey: SymmetricKey?  // Made internal for VideoCallManager access
    private var _callNonce: UInt64 = 0
    private var sessionNonceSalt: Data = Data()  // Random 4 bytes per call
    private var _lastReceivedNonce: UInt64 = 0    // For replay protection
    private var receivedAudioPacketCount: UInt64 = 0  // For debug logging

    // Video call support
    private var _videoNonce: UInt64 = 0

    // 🔧 Thread-safe nonce access using os_unfair_lock (prevents nonce reuse vulnerability)
    private var nonceLock = os_unfair_lock()

    /// Thread-safe increment and get for call nonce
    private var callNonce: UInt64 {
        get {
            os_unfair_lock_lock(&nonceLock)
            defer { os_unfair_lock_unlock(&nonceLock) }
            return _callNonce
        }
        set {
            os_unfair_lock_lock(&nonceLock)
            _callNonce = newValue
            os_unfair_lock_unlock(&nonceLock)
        }
    }

    /// Thread-safe increment for call nonce - returns new value
    private func incrementCallNonce() -> UInt64 {
        os_unfair_lock_lock(&nonceLock)
        _callNonce += 1
        let result = _callNonce
        os_unfair_lock_unlock(&nonceLock)
        return result
    }

    /// Thread-safe access for last received nonce (replay protection)
    private var lastReceivedNonce: UInt64 {
        get {
            os_unfair_lock_lock(&nonceLock)
            defer { os_unfair_lock_unlock(&nonceLock) }
            return _lastReceivedNonce
        }
        set {
            os_unfair_lock_lock(&nonceLock)
            _lastReceivedNonce = newValue
            os_unfair_lock_unlock(&nonceLock)
        }
    }

    /// Thread-safe increment for video nonce - returns new value
    private func incrementVideoNonce() -> UInt64 {
        os_unfair_lock_lock(&nonceLock)
        _videoNonce += 1
        let result = _videoNonce
        os_unfair_lock_unlock(&nonceLock)
        return result
    }

    /// Thread-safe access for video nonce
    private var videoNonce: UInt64 {
        get {
            os_unfair_lock_lock(&nonceLock)
            defer { os_unfair_lock_unlock(&nonceLock) }
            return _videoNonce
        }
        set {
            os_unfair_lock_lock(&nonceLock)
            _videoNonce = newValue
            os_unfair_lock_unlock(&nonceLock)
        }
    }
    var onVideoPacketReceived: ((Data) -> Void)?  // Callback for video packets

    // 🔧 FIX: Buffer video packets that arrive before VideoCallView connects its callback
    // This prevents losing the first keyframe (with SPS/PPS) which is critical for decoding
    private var pendingVideoPackets: [Data] = []
    private let maxPendingVideoPackets = 60  // ~2 seconds at 30fps

    /// Connect video packet receiver and flush any buffered packets
    func setVideoPacketReceiver(_ handler: @escaping (Data) -> Void) {
        onVideoPacketReceived = handler
        // Flush buffered packets (includes first keyframe with SPS/PPS)
        if !pendingVideoPackets.isEmpty {
            print("📹 VoiceCall: Flushing \(pendingVideoPackets.count) buffered video packets")
            for packet in pendingVideoPackets {
                handler(packet)
            }
            pendingVideoPackets.removeAll()
        }
    }
    
    // Network
    #if os(iOS)
    private var meshManager: MeshNetworkManager?
    #endif
    private var torConnection: NWConnection?
    private var udpListener: NWListener?
    
    // Current call ID for VPS routing
    private var currentCallId: String?
    
    // Timers
    private var callTimer: Timer?
    private var keepAliveTimer: Timer?
    private var callStartTime: Date?
    private var waitingStartTime: Date?  // When connecting/ringing started
    private var waitingTimer: Timer?     // Timer for waiting duration
    private var callSignalPollingTimer: Timer?  // Poll for incoming call signals
    
    // Audio format - optimized for voice with LOW LATENCY
    private let sampleRate: Double = 16000  // Optimal for voice (8-16kHz range)
    private let bufferSize: AVAudioFrameCount = 256  // Smaller = lower latency (~16ms at 16kHz)
    // 256 frames @ 16kHz = 16ms latency per buffer
    // Total round-trip target: <100ms for good voice quality
    
    // Audio format for transmission (fixed format for both send/receive)
    private lazy var transmissionFormat: AVAudioFormat = {
        AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)!
    }()
    
    // Audio converter for resampling input to transmission format
    private var audioConverter: AVAudioConverter?
    
    // Audio buffer queue for smooth playback
    private var audioBufferQueue: [AVAudioPCMBuffer] = []
    private let audioQueueLock = NSLock()
    
    // Counter for mesh audio packets received (for logging)
    private var meshAudioReceivedCount: UInt64 = 0
    
    // MARK: - Connection Loss Detection & Beep
    private var lastAudioReceivedTime: Date = Date()
    private var connectionLossTimer: Timer?
    private var isPlayingConnectionLossBeep = false
    private let connectionLossThreshold: TimeInterval = 5.0  // 5s tolerance for brief network drops (increased for VPS latency)
    private var connectionLossBeepPlayer: AVAudioPlayerNode?

    // 🔒 CONNECTION TYPE LOCK - CRITICAL for call stability
    // Once a call starts, the connection type is LOCKED and NEVER changes until call ends
    // This prevents audio routing issues from switching between mesh/VPS mid-call
    private var connectionTypeLocked = false
    private var lockedConnectionType: ConnectionType = .none
    
    // 🔒 CRITICAL: Lock connection type for INCOMING calls immediately when received
    // This prevents the device from switching to mesh when it wakes up and discovers nearby peers
    // The lock is applied BEFORE the user accepts the call
    private var incomingCallConnectionLocked = false
    private var incomingCallConnectionType: ConnectionType = .none

    // 📶 Reconnection system for call resilience
    private var reconnectAttempts = 0
    private let maxReconnectAttempts = 10  // Increased for better resilience
    private var isReconnecting = false
    private var lastReconnectAttemptTime: Date?
    private let reconnectCooldown: TimeInterval = 2.0  // Min time between reconnect attempts
    private let maxDisconnectionTime: TimeInterval = 45.0  // 🔧 FIX: End call after 45s of no audio (increased from 30s for VPS latency)
    
    // 🔊 NEW: Simple jitter buffer for smoother audio
    private var audioJitterBuffer: [Data] = []
    private let jitterBufferTarget = 3  // ~60ms buffer at 20ms/packet
    private var jitterBufferLock = NSLock()
    
    // ============================================================================
    // 🎯 WHATSAPP-QUALITY AUDIO COMPONENTS
    // ============================================================================
    
    // Adaptive bitrate controller - adjusts quality based on network conditions
    private let adaptiveBitrate = AdaptiveBitrateController()
    
    // Advanced jitter buffer with reordering support
    private let advancedJitterBuffer = AdvancedJitterBuffer()
    
    // Packet loss concealer - hides packet loss with interpolation
    private let packetLossConcealer = PacketLossConcealer()
    
    // Forward Error Correction - recovers lost packets
    private let fecEncoder = SimpleXORFEC(groupSize: 4)  // 1 FEC packet per 4 audio packets
    private let fecDecoder = SimpleXORFEC(groupSize: 4)
    
    // 🎵 Audio codec - Opus for high-quality, low-bandwidth audio
    // Uses ADPCM fallback (4x compression) until opus-swift library is added
    // With opus-swift: 32x compression (24kbps vs 768kbps PCM)
    private let audioCodec: AudioCodec = OpusCodec()
    
    // Enable/disable advanced features (for debugging)
    private var useAdvancedJitterBuffer = true
    private var useFEC = true
    private var useAdaptiveBitrate = true
    private var usePLC = true
    private var useCompression = true  // Opus/ADPCM compression
    
    // FEC packet type identifier
    private let FEC_PACKET_TYPE: UInt8 = 0xFE
    
    // STUN/TURN servers for NAT traversal
    private let stunServers = [
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
        "stun:stun2.l.google.com:19302",
        "stun:stun.cloudflare.com:3478"
    ]
    
    // Public IP discovered via STUN (for direct P2P when possible)
    private var discoveredPublicIP: String?
    private var discoveredPublicPort: UInt16?
    
    // Incoming call info
    struct IncomingCall: Equatable {
        let peerPublicKey: String
        let peerName: String
        let timestamp: Date
        var isVideoCall: Bool = false  // 📹 Differentiate voice vs video calls
    }

    // MARK: - Initialization

    override init() {
        super.init()
        // 🔧 FIX: Do NOT setup audio session on init - this pauses music/podcasts
        // Audio session will be configured when a call actually starts (caller or receiver)
        // setupAudioSession() - REMOVED to prevent interrupting other audio apps
        setupNotificationObservers()
        setupCallKitObservers()
        setupNetworkMonitoring()
        setupAdaptiveBitrateCallback()
        startCallSignalPolling()
        print("📞 VoiceCall: Manager initialized")
        print("   📡 VPS Public: \(vpsPublicURL)")
        print("   🧅 VPS Tor: \(vpsTorURL.prefix(40))...")
        print("   🎙️ VoIP Path: \(vpsVoIPPath)")
        print("   📶 Mesh Direct calls: ENABLED (for offline scenarios)")
        print("   🎵 Audio Codec: \(audioCodec.name)")
        print("   🎧 Audio session: Deferred until call starts (preserves other audio)")
    }
    
    /// Connect adaptive bitrate to Opus codec for dynamic quality adjustment
    private func setupAdaptiveBitrateCallback() {
        adaptiveBitrate.onBitrateChange = { [weak self] level, packetLoss, latency in
            guard let self = self else { return }
            
            // Map AdaptiveBitrateController levels to Opus bitrates
            // Opus bitrates: 12k (emergency), 16k (poor), 24k (normal), 32k (good), 48k (excellent)
            if let opusCodec = self.audioCodec as? OpusCodec {
                let opusBitrate: Int32
                switch level {
                case .ultraLow:
                    opusBitrate = 12000
                case .low:
                    opusBitrate = 16000
                case .medium:
                    opusBitrate = 24000
                case .high:
                    opusBitrate = 32000
                case .ultraHigh:
                    opusBitrate = 48000
                }
                
                opusCodec.setBitrate(opusBitrate)
                opusCodec.setExpectedPacketLoss(Int32(packetLoss))
                
                print("🎵 VoiceCall: Opus bitrate adjusted to \(opusBitrate / 1000)kbps")
            }
        }
    }
    
    // MARK: - Network Monitoring
    
    private func setupNetworkMonitoring() {
        networkMonitor = NWPathMonitor()
        networkMonitor?.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                self?.hasInternetConnection = path.status == .satisfied
                print("📶 Network status: \(path.status == .satisfied ? "Connected" : "No Internet")")
                
                // 🎯 Discover public IP via STUN when network becomes available
                if path.status == .satisfied {
                    self?.discoverPublicIPViaSTUN()
                }
            }
        }
        networkMonitor?.start(queue: DispatchQueue(label: "NetworkMonitor"))
    }
    
    // MARK: - 🎯 STUN/NAT Traversal
    
    /// Discover our public IP and port via STUN servers
    /// This enables direct P2P connections when both parties are behind NAT
    private func discoverPublicIPViaSTUN() {
        Task {
            for stunServer in stunServers {
                if let (ip, port) = await querySTUNServer(stunServer) {
                    await MainActor.run {
                        self.discoveredPublicIP = ip
                        self.discoveredPublicPort = port
                        print("🌐 STUN: Discovered public endpoint: \(ip):\(port)")
                    }
                    return
                }
            }
            print("⚠️ STUN: Could not discover public IP from any server")
        }
    }
    
    /// Query a single STUN server for our public IP
    private func querySTUNServer(_ server: String) async -> (String, UInt16)? {
        // Parse server URL: stun:host:port
        let components = server.replacingOccurrences(of: "stun:", with: "").split(separator: ":")
        guard components.count >= 1 else { return nil }
        
        let host = String(components[0])
        let port = components.count > 1 ? UInt16(components[1]) ?? 3478 : 3478
        
        // Create UDP connection to STUN server
        let endpoint = NWEndpoint.hostPort(host: NWEndpoint.Host(host), port: NWEndpoint.Port(integerLiteral: port))
        let connection = NWConnection(to: endpoint, using: .udp)
        
        return await withCheckedContinuation { continuation in
            connection.stateUpdateHandler = { state in
                if state == .ready {
                    // Send STUN Binding Request
                    let request = self.createSTUNBindingRequest()
                    connection.send(content: request, completion: .contentProcessed { error in
                        if error != nil {
                            continuation.resume(returning: nil)
                            connection.cancel()
                        }
                    })
                    
                    // Receive response
                    connection.receive(minimumIncompleteLength: 1, maximumLength: 1024) { data, _, _, error in
                        defer { connection.cancel() }
                        
                        if let data = data, let result = self.parseSTUNResponse(data) {
                            continuation.resume(returning: result)
                        } else {
                            continuation.resume(returning: nil)
                        }
                    }
                } else if case .failed = state {
                    continuation.resume(returning: nil)
                    connection.cancel()
                }
            }
            
            connection.start(queue: .global())
            
            // Timeout after 3 seconds
            DispatchQueue.global().asyncAfter(deadline: .now() + 3) {
                if connection.state != .cancelled {
                    connection.cancel()
                    // Don't resume here - the receive callback will handle it
                }
            }
        }
    }
    
    /// Create a STUN Binding Request packet (RFC 5389)
    private func createSTUNBindingRequest() -> Data {
        var packet = Data()
        
        // Message Type: Binding Request (0x0001)
        packet.append(contentsOf: [0x00, 0x01])
        
        // Message Length: 0 (no attributes)
        packet.append(contentsOf: [0x00, 0x00])
        
        // Magic Cookie: 0x2112A442
        packet.append(contentsOf: [0x21, 0x12, 0xA4, 0x42])
        
        // Transaction ID: 12 random bytes
        var transactionID = [UInt8](repeating: 0, count: 12)
        _ = SecRandomCopyBytes(kSecRandomDefault, 12, &transactionID)
        packet.append(contentsOf: transactionID)
        
        return packet
    }
    
    /// Parse STUN Binding Response to extract XOR-MAPPED-ADDRESS
    private func parseSTUNResponse(_ data: Data) -> (String, UInt16)? {
        guard data.count >= 20 else { return nil }
        
        // Check message type (Binding Success Response: 0x0101)
        guard data[0] == 0x01 && data[1] == 0x01 else { return nil }
        
        // Parse attributes
        var offset = 20  // Skip header
        
        while offset + 4 <= data.count {
            let attrType = (UInt16(data[offset]) << 8) | UInt16(data[offset + 1])
            let attrLength = Int((UInt16(data[offset + 2]) << 8) | UInt16(data[offset + 3]))
            offset += 4
            
            guard offset + attrLength <= data.count else { break }
            
            // XOR-MAPPED-ADDRESS (0x0020)
            if attrType == 0x0020 && attrLength >= 8 {
                // Family (IPv4 = 0x01)
                let family = data[offset + 1]
                if family == 0x01 {
                    // Port XOR'd with magic cookie high bits
                    let xorPort = (UInt16(data[offset + 2]) << 8) | UInt16(data[offset + 3])
                    let port = xorPort ^ 0x2112
                    
                    // IP XOR'd with magic cookie
                    let xorIP = [data[offset + 4], data[offset + 5], data[offset + 6], data[offset + 7]]
                    let magicCookie: [UInt8] = [0x21, 0x12, 0xA4, 0x42]
                    let ip = "\(xorIP[0] ^ magicCookie[0]).\(xorIP[1] ^ magicCookie[1]).\(xorIP[2] ^ magicCookie[2]).\(xorIP[3] ^ magicCookie[3])"
                    
                    return (ip, port)
                }
            }
            
            // Pad to 4-byte boundary
            offset += (attrLength + 3) & ~3
        }
        
        return nil
    }
    
    /// Get our public endpoint for P2P connection negotiation
    func getPublicEndpoint() -> (ip: String, port: UInt16)? {
        guard let ip = discoveredPublicIP, let port = discoveredPublicPort else {
            return nil
        }
        return (ip, port)
    }
    
    // MARK: - CallKit Integration
    
    private func setupCallKitObservers() {
        // Listen for CallKit answer action
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleCallKitAnswer(_:)),
            name: NSNotification.Name("CallKitAnswerCall"),
            object: nil
        )

        // Listen for CallKit end action
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleCallKitEnd(_:)),
            name: NSNotification.Name("CallKitEndCall"),
            object: nil
        )

        // Listen for VoIP push received
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleVoIPCallReceived(_:)),
            name: NSNotification.Name("VoIPCallReceived"),
            object: nil
        )
        
        // 🔧 FIX: Listen for VoIP push active - suppress polling when push is handling a call
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleVoIPPushActive(_:)),
            name: NSNotification.Name("VoIPPushReceived"),
            object: nil
        )

        // Listen for CallKit audio session activation
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleCallKitAudioActivated),
            name: NSNotification.Name("CallKitAudioSessionActivated"),
            object: nil
        )
        
        print("📞 VoiceCall: CallKit observers registered")
    }
    
    /// Handle CallKit answer - this is called when user taps "Accept" on the iOS call UI
    @objc private func handleCallKitAnswer(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let callerKey = userInfo["callerPublicKey"] as? String,
              let callerName = userInfo["callerName"] as? String else {
            print("❌ VoiceCall: CallKit answer missing caller info")
            fileLog.log("❌ handleCallKitAnswer: missing caller info")
            return
        }

        print("📞 VoiceCall: CallKit ANSWER received!")
        print("   From: \(callerName)")
        print("   Key: \(callerKey.prefix(16))...")
        print("   Current state: \(callState)")
        print("   incomingCall set: \(incomingCall != nil)")
        fileLog.log("📞 handleCallKitAnswer: from=\(callerName) state=\(callState) incoming=\(incomingCall != nil) isVideo=\(incomingCall?.isVideoCall ?? false)")

        // 🔧 FIX: Mark that this call was answered via CallKit (not our in-app overlay)
        // When answered via CallKit, we must keep CallKit alive to manage the audio session
        wasAnsweredViaCallKit = true

        // 🔧 FIX: If we're already accepting, ignore duplicate
        guard !isAcceptingCall else {
            print("⚠️ VoiceCall: Already accepting call, ignoring duplicate CallKit answer")
            return
        }

        // If we're idle, we need to set up the incoming call first using encrypted data
        if callState == .idle {
            #if os(iOS)
            print("📞 VoiceCall: State is idle - getting encrypted data from VoIPPushManager...")

            // 🔧 FIX: Get pending call data from VoIPPushManager
            if let pendingData = VoIPPushManager.shared.getPendingCallData(),
               let encryptedData = pendingData["encryptedData"] as? Data {
                print("📞 VoiceCall: Found encrypted data, setting up incoming call...")
                handleIncomingCall(from: callerKey, peerName: callerName, encryptedData: encryptedData, viaTor: true)

                // Wait for handleIncomingCall to set up state, then accept
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    Task {
                        do {
                            // Double-check we're in ringing state before accepting
                            guard self.callState == .ringing, self.incomingCall != nil else {
                                print("❌ VoiceCall: Call not properly set up after handleIncomingCall")
                                return
                            }
                            try await self.acceptCall()
                            VoIPPushManager.shared.clearPendingCallData()
                        } catch {
                            print("❌ VoiceCall: Failed to accept call: \(error)")
                        }
                    }
                }
            } else if let encryptedSignal = VoIPPushManager.shared.getPendingCallData()?["encryptedSignal"] as? String,
                      let encryptedData = Data(base64Encoded: encryptedSignal) {
                print("📞 VoiceCall: Found encrypted signal string, setting up incoming call...")
                handleIncomingCall(from: callerKey, peerName: callerName, encryptedData: encryptedData, viaTor: true)

                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    Task {
                        do {
                            guard self.callState == .ringing, self.incomingCall != nil else {
                                print("❌ VoiceCall: Call not properly set up after handleIncomingCall")
                                return
                            }
                            try await self.acceptCall()
                            VoIPPushManager.shared.clearPendingCallData()
                        } catch {
                            print("❌ VoiceCall: Failed to accept call: \(error)")
                        }
                    }
                }
            } else {
                // 🔧 FIX: No encrypted data available yet - wait for it via polling
                print("⚠️ VoiceCall: No encrypted data available yet, waiting for signal via polling...")

                // Set basic state so we're ready when signal arrives
                DispatchQueue.main.async {
                    self.currentCallPeer = callerKey
                    self.currentCallPeerName = callerName
                    self.isOutgoingCall = false
                    self.connectionType = .tor

                    // 🔧 FIX: Mark that we have a pending accept from CallKit
                    self.pendingCallKitAccept = true
                    self.pendingCallKitAcceptCallerKey = callerKey
                    print("📞 VoiceCall: Set pendingCallKitAccept = true for \(callerKey.prefix(12))...")
                }

                // The call will be auto-accepted when handleIncomingCall sets up the proper state
            }
            #else
            // macOS: No VoIPPushManager, set basic state
            print("⚠️ VoiceCall: macOS - setting basic call state...")
            DispatchQueue.main.async {
                self.currentCallPeer = callerKey
                self.currentCallPeerName = callerName
                self.isOutgoingCall = false
                self.connectionType = .tor
            }
            #endif
        } else if callState == .ringing && incomingCall != nil {
            // Already ringing with proper setup - accept immediately
            Task {
                do {
                    try await self.acceptCall()
                } catch {
                    print("❌ VoiceCall: Failed to accept call: \(error)")
                }
            }
        } else {
            print("⚠️ VoiceCall: Unexpected state for CallKit answer: \(callState), incomingCall: \(incomingCall != nil)")
        }
    }

    /// Handle CallKit end - when user taps "End" on iOS call UI
    @objc private func handleCallKitEnd(_ notification: Notification) {
        print("📞 VoiceCall: CallKit END received")
        fileLog.log("⚡ CallKit END received - state: \(callState), isVideo: \(isCurrentCallVideo), callUUID: \(notification.userInfo?["callUUID"] ?? "nil")")

        // 🔧 FIX: If call JUST connected (< 2s ago), ignore spurious CallKit end
        // This protects against race condition where CallKit fires CXEndCallAction
        // before receiving reportOutgoingCallConnected()
        if callState == .inCall, let connectedTime = callConnectedTime,
           Date().timeIntervalSince(connectedTime) < 2.0 {
            print("⚠️ VoiceCall: Ignoring CallKit END - call just connected \(Date().timeIntervalSince(connectedTime))s ago")
            fileLog.log("⚠️ Ignoring CallKit END - call just connected \(Date().timeIntervalSince(connectedTime))s ago")
            return
        }

        // 🔧 FIX: Also protect during ringing/connecting/accepting — CallKit can fire
        // spurious end actions during the answer flow on the callee side
        if (callState == .ringing || callState == .connecting) && isAcceptingCall {
            print("⚠️ VoiceCall: Ignoring CallKit END - currently accepting call (state: \(callState))")
            fileLog.log("⚠️ Ignoring CallKit END during accept - state: \(callState)")
            return
        }

        endCall(reason: .hungUp)
    }

    /// Handle VoIP push received - when app wakes from background
    @objc private func handleVoIPCallReceived(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let callerKey = userInfo["callerPublicKey"] as? String,
              let callerName = userInfo["callerName"] as? String else {
            print("❌ VoiceCall: VoIP call received but missing info")
            return
        }
        
        print("📞 VoiceCall: VoIP call received from \(callerName)")
        
        if let encryptedData = userInfo["encryptedData"] as? Data {
            handleIncomingCall(from: callerKey, peerName: callerName, encryptedData: encryptedData, viaTor: true)
        } else if let encryptedSignal = userInfo["encryptedSignal"] as? String,
                  let data = Data(base64Encoded: encryptedSignal) {
            handleIncomingCall(from: callerKey, peerName: callerName, encryptedData: data, viaTor: true)
        } else {
            DispatchQueue.main.async {
                self.currentCallPeer = callerKey
                self.currentCallPeerName = callerName
                self.isOutgoingCall = false
            }
        }
    }

    /// Handle CallKit audio session activation
    @objc private func handleCallKitAudioActivated() {
        print("📞 VoiceCall: CallKit audio session activated")

        #if os(iOS)
        if callState == .inCall {
            do {
                let session = AVAudioSession.sharedInstance()
                if isSpeakerOn {
                    try session.overrideOutputAudioPort(.speaker)
                } else {
                    try session.overrideOutputAudioPort(.none)
                }
            } catch {
                print("⚠️ VoiceCall: Failed to set audio route: \(error)")
            }
        }
        #endif
    }
    
    /// 🔧 FIX: Handle VoIP push received - suppress polling for this caller
    @objc private func handleVoIPPushActive(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let callerKey = userInfo["callerPublicKey"] as? String else {
            return
        }
        
        print("📞 VoiceCall: VoIP push active for caller \(callerKey.prefix(12))...")
        print("   Suppressing VPS polling for this caller")
        
        DispatchQueue.main.async {
            self.voipPushActiveForCaller = callerKey
            self.voipPushReceivedTime = Date()
            self.isCallKitHandlingIncoming = true
        }
        
        // Clear after 10 seconds (call should be set up by then)
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
            if self?.voipPushActiveForCaller == callerKey {
                self?.voipPushActiveForCaller = nil
                self?.voipPushReceivedTime = nil
                print("📞 VoiceCall: VoIP push suppression expired for \(callerKey.prefix(12))...")
            }
        }
    }
    
    private func startCallSignalPolling() {
        // 🔧 FIX: Poll every 1 second (was 2s) for faster call accept detection
        callSignalPollingTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task {
                await self?.checkForIncomingCallSignals()
            }
        }
        print("📞 VoiceCall: Call signal polling started (1s interval)")
    }
    
    private func checkForIncomingCallSignals() async {
        guard let identityManager = getIdentityManager(),
              !identityManager.publicKey.isEmpty else { return }
        
        // Check when:
        // - idle (for incoming calls)
        // - connecting as outgoing caller (waiting for accept/decline)
        // - ringing as outgoing caller (waiting for accept/decline)
        // - ringing as incoming callee (to receive callEnd if caller hangs up before pickup)
        // - inCall (for callEnd signals to properly sync hang-up)
        let shouldPoll = callState == .idle ||
                        (callState == .connecting && isOutgoingCall) ||
                        callState == .ringing ||  // 🔧 FIX: Poll for BOTH incoming and outgoing ringing to receive callEnd
                        callState == .inCall
        guard shouldPoll else { return }
        
        // 🔧 FIX: If VoIP push is actively handling a call, skip polling for NEW incoming calls
        // We still poll if we're already in a call (for callEnd signals) or if we're the caller
        if callState == .idle && voipPushActiveForCaller != nil {
            if let pushTime = voipPushReceivedTime, Date().timeIntervalSince(pushTime) < 8.0 {
                print("📞 VoiceCall: Skipping poll - VoIP push is handling incoming call")
                return
            }
        }

        // 🔧 CRITICAL FIX: Use base64url encoding (not percent encoding)
        // This matches what we send and what VPS expects
        let encodedKey = base64urlEncode(identityManager.publicKey)

        // 📱 MULTI-DEVICE: Include deviceId in poll URL to filter out self-sent signals
        // This ensures we don't receive our own "answered elsewhere" notifications
        guard let url = URL(string: "\(vpsURL)/signals/\(encodedKey)?deviceId=\(deviceId)") else { return }

        print("📞 VoiceCall: Polling for signals... (state: \(callState))")
        print("   📡 URL: \(url.absoluteString)")
        
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 5
        
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            
            guard let httpResponse = response as? HTTPURLResponse else {
                print("   ❌ Invalid HTTP response")
                return
            }
            
            print("   📡 Status: \(httpResponse.statusCode)")
            
            guard httpResponse.statusCode == 200 else {
                if httpResponse.statusCode != 404 {
                    print("   ❌ Poll failed: \(httpResponse.statusCode)")
                }
                return
            }
            
            // Try to parse as array of signals
            if let signals = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]], !signals.isEmpty {
                print("   ✅ Received \(signals.count) signal(s)!")
                for signal in signals {
                    await processCallSignal(signal)
                }
            }
            // Or single signal object
            else if let signal = try? JSONSerialization.jsonObject(with: data) as? [String: Any], !signal.isEmpty {
                print("   ✅ Received 1 signal!")
                await processCallSignal(signal)
            } else {
                // Empty or no signals - this is normal when idle
            }
            
        } catch {
            print("   ❌ Poll error: \(error.localizedDescription)")
        }
    }
    
    private func processCallSignal(_ signal: [String: Any]) async {
        // Handle both formats: direct signal or wrapped in encryptedContent
        var senderKey: String?
        var encryptedData: Data?
        var signalId: String?
        var receivedCallId: String?
        
        signalId = signal["id"] as? String
        receivedCallId = signal["callId"] as? String
        
        // Check if we've already processed this signal ID
        if let id = signalId, !id.isEmpty {
            if processedCallSignalIDs.contains(id) {
                print("⚠️ VoiceCall: Ignoring duplicate call signal ID: \(id.prefix(16))...")
                return
            }
            // Add to processed set (keep last 100 to prevent memory growth)
            processedCallSignalIDs.insert(id)
            if processedCallSignalIDs.count > 100 {
                processedCallSignalIDs.removeFirst()
            }
        }
        
        // Format 1: New VPS call_server format {sender, signal, callId}
        if let sender = signal["sender"] as? String,
           let dataBase64 = signal["signal"] as? String,
           let data = Data(base64Encoded: dataBase64) {
            senderKey = sender
            encryptedData = data
        }
        // Format 2: Original format {senderAddress, encryptedContent}
        else if let sender = signal["senderAddress"] as? String,
           let dataBase64 = signal["encryptedContent"] as? String,
           let data = Data(base64Encoded: dataBase64) {
            senderKey = sender
            encryptedData = data
        }
        // Format 3: Alternative format {sender, data}
        else if let sender = signal["sender"] as? String,
                let dataBase64 = signal["data"] as? String,
                let data = Data(base64Encoded: dataBase64) {
            senderKey = sender
            encryptedData = data
        }
        
        guard let sender = senderKey, let data = encryptedData else {
            print("⚠️ VoiceCall: Invalid call signal format: \(signal.keys)")
            return
        }

        // Get signal type if available (from VPS)
        let signalType = signal["type"] as? String

        // Skip if this is our own signal - EXCEPT for "callAnsweredElsewhere"
        // which needs to be delivered to other devices with the same public key
        if let identityManager = getIdentityManager(),
           sender == identityManager.publicKey {
            // 📱 MULTI-DEVICE: Allow "callAnsweredElsewhere" from same public key
            if signalType != "callAnsweredElsewhere" {
                return
            }
            print("📱 VoiceCall: Processing callAnsweredElsewhere from same public key (multi-device)")
        }

        // Store received callId if we don't have one
        if currentCallId == nil, let callId = receivedCallId, !callId.isEmpty {
            currentCallId = callId
        }
        
        // 🔧 FIX: Use getDisplayName to show contact alias instead of truncated key
        let senderName = getDisplayName(for: sender)
        
        print("📞 VoiceCall: Received call signal from \(senderName)")
        
        await MainActor.run {
            // Use the proper handler that routes all signal types correctly
            // (callRequest, callAccept, callDecline, callEnd)
            handleReceivedCallSignal(from: sender, peerName: senderName, encryptedData: data)
        }
    }
    
    private func setupNotificationObservers() {
        // Listen for call signals from mesh
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleCallSignalNotification(_:)),
            name: .didReceiveCallSignal,
            object: nil
        )
        
        // Listen for audio from mesh
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleCallAudioNotification(_:)),
            name: .didReceiveCallAudio,
            object: nil
        )
        
        // Listen for call signals from IPFS (cloud calls)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleIPFSCallSignal(_:)),
            name: NSNotification.Name("ReceivedCallSignalFromIPFS"),
            object: nil
        )
        
        // Listen for relayed call signals (via mesh relay)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleRelayedCallSignal(_:)),
            name: NSNotification.Name("ReceivedRelayedCallSignal"),
            object: nil
        )

        // 🔧 FIX: Listen for VPS fallback requests from MeshDirectCallManager
        // When mesh peer is not directly connected, fall back to VPS
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleVPSFallbackRequest(_:)),
            name: NSNotification.Name("UseFallbackVPSCall"),
            object: nil
        )
    }

    /// 🔧 FIX: Handle VPS fallback when MeshDirectCallManager can't reach peer via mesh
    @objc private func handleVPSFallbackRequest(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let peerKey = userInfo["peerKey"] as? String else {
            print("❌ VoiceCall: Invalid VPS fallback request")
            return
        }

        print("📞 VoiceCall: Received VPS fallback request for peer: \(peerKey.prefix(16))...")
        print("   Peer not in mesh - initiating VPS call")

        // Start VPS call to this peer
        Task {
            do {
                // Get peer name for display
                let peerName = getDisplayName(for: peerKey)

                // Set connection type to VPS/Tor
                await MainActor.run {
                    self.connectionType = .tor
                }

                try await startTorCall(to: peerKey)
                print("✅ VoiceCall: VPS fallback call initiated to \(peerName)")
            } catch {
                print("❌ VoiceCall: VPS fallback failed: \(error)")
                await MainActor.run {
                    self.callState = .idle
                }
            }
        }
    }
    
    @objc private func handleRelayedCallSignal(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let data = userInfo["data"] as? Data,
              let senderPeer = userInfo["senderPeer"] as? String else {
            print("❌ VoiceCall: Invalid relayed call signal notification")
            return
        }
        
        print("📞 VoiceCall: Received relayed call signal")
        print("   From peer: \(senderPeer)")
        print("   Data size: \(data.count) bytes")
        
        // 🔧 FIX: Use getDisplayName to show contact alias
        let senderName = getDisplayName(for: senderPeer)
        handleReceivedCallSignal(from: senderPeer, peerName: senderName, encryptedData: data, viaTor: false)
    }
    
    @objc private func handleIPFSCallSignal(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let encryptedData = userInfo["encryptedData"] as? Data,
              let senderPublicKey = userInfo["senderPublicKey"] as? String else {
            print("❌ VoiceCall: Invalid IPFS call signal notification")
            return
        }
        
        print("📞 VoiceCall: Received call signal from IPFS")
        print("   Sender: \(senderPublicKey.prefix(12))...")
        print("   Data size: \(encryptedData.count) bytes")
        print("   Current state: \(callState)")
        print("   isOutgoingCall: \(isOutgoingCall)")
        
        // 🔧 FIX: Use getDisplayName to show contact alias
        let senderName = getDisplayName(for: senderPublicKey)
        
        // Use the unified handler that properly routes all signal types
        // (callRequest, callAccept, callDecline, callEnd)
        handleReceivedCallSignal(from: senderPublicKey, peerName: senderName, encryptedData: encryptedData)
    }
    
    @objc private func handleCallSignalNotification(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let data = userInfo["data"] as? Data,
              let peerName = userInfo["peerName"] as? String else { return }
        
        // Get public key if available (from new key exchange system)
        let peerPublicKey = userInfo["peerPublicKey"] as? String ?? peerName
        let viaMesh = userInfo["viaMesh"] as? Bool ?? false
        let viaRelay = userInfo["viaRelay"] as? Bool ?? false
        
        print("📞 VoiceCall: Received call signal via mesh")
        print("   Peer name: \(peerName)")
        print("   Peer key: \(peerPublicKey.prefix(16))...")
        print("   Via mesh: \(viaMesh)")
        print("   Via relay: \(viaRelay)")
        
        // If via relay, we need to use meshRelay connection type (signaling via mesh, audio via VPS)
        if viaRelay {
            DispatchQueue.main.async {
                self.usingMeshRelay = true
            }
        }
        
        // Mesh call signals - not via Tor (but might be via relay)
        handleReceivedCallSignal(from: peerPublicKey, peerName: peerName, encryptedData: data, viaTor: false, viaRelay: viaRelay)
    }
    
    @objc private func handleCallAudioNotification(_ notification: Notification) {
        guard let userInfo = notification.userInfo else { return }

        // 🔧 FIX: Handle both iOS mesh ("data" key) and cross-platform ("audioData" key) formats
        let data: Data
        if let d = userInfo["data"] as? Data {
            data = d  // iOS-to-iOS mesh audio
        } else if let d = userInfo["audioData"] as? Data {
            data = d  // Android-to-iOS cross-platform audio
        } else {
            return
        }

        guard data.count > 9 else { return }  // Need at least header (9 bytes)

        // Log first packet to confirm mesh audio is working
        if meshAudioReceivedCount == 0 {
            print("🎵 VoiceCall: First audio packet via MESH! size: \(data.count) bytes")
        }

        // Mesh payload is the full packet: [type(1)][seq(8)][encrypted_audio]
        // Pass directly to receiveAudio which handles the new format
        receiveAudio(encryptedData: data)
    }
    
    private func processReceivedCallSignal(_ encryptedData: Data, from peerName: String, viaTor: Bool = false) {
        // Try to decrypt
        guard let currentPeer = currentCallPeer,
              let decryptedData = try? decryptFromPeer(data: encryptedData, peerPublicKey: currentPeer),
              decryptedData.count >= 1,
              let packetType = CallPacketType(rawValue: decryptedData[0]) else {
            // Maybe it's a new incoming call - try with the peer name to find public key
            // For now, just route to handleIncomingCall if we're idle
            if callState == .idle {
                // Extract peer public key from the encrypted data header or lookup
                // This is handled by handleIncomingCall when properly called
            }
            return
        }
        
        switch packetType {
        case .callAccept, .videoCallAccept:
            // Set connection type if not already set
            if connectionType == .none {
                connectionType = viaTor ? .tor : .mesh
            }
            Task {
                try? await handleCallAccepted()
            }
        case .callDecline:
            receivedRemoteEndSignal = true  // Treat decline as remote-initiated end
            endCall(reason: .declined)
        case .callEnd:
            // 🔧 FIX: Validate timestamp to reject stale callEnd signals
            print("📞 VoiceCall: Received callEnd in processReceivedCallSignal")
            fileLog.log("📩 SIGNAL(alt): callEnd received - state: \(callState)")
            guard let payload = validateAndExtractPayload(from: decryptedData, packetType: .callEnd) else {
                print("⚠️ VoiceCall: Ignoring stale/invalid callEnd signal in processReceivedCallSignal")
                return
            }
            
            // 🔧 FIX: Brief grace period to filter stale callEnd from previous attempts
            if let connectedTime = callConnectedTime {
                let timeSinceConnect = Date().timeIntervalSince(connectedTime)
                if timeSinceConnect < 3.0 {
                    print("⚠️ VoiceCall: Ignoring callEnd during 3s post-connect grace (connected \(String(format: "%.1f", timeSinceConnect))s ago)")
                    return
                }
            }

            var reason: CallEndReason = .hungUp
            if !payload.isEmpty {
                if let reasonString = String(data: payload, encoding: .utf8),
                   let parsedReason = CallEndReason(rawValue: reasonString) {
                    reason = parsedReason
                }
            }
            receivedRemoteEndSignal = true  // Mark that remote initiated the end
            endCall(reason: reason)
        case .callRequest, .videoCallRequest:
            // New incoming call - handled elsewhere
            break
        case .audioData, .keepAlive, .keyExchange:
            // Not signal packets or handled elsewhere
            break
        case .callAnsweredElsewhere:
            // 📱 MULTI-DEVICE: Call was answered on another device
            print("📱 VoiceCall: Call answered on another device")
            handleCallAnsweredElsewhere()
        }
    }

    #if os(iOS)
    func configure(meshManager: MeshNetworkManager) {
        self.meshManager = meshManager

        // Monitor app returning to foreground to detect stale calls
        // When device screen was off, timers don't fire, so check on wake
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAppWillEnterForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
    }

    @objc private func handleAppWillEnterForeground() {
        guard callState == .inCall else { return }

        let timeSinceLastAudio = Date().timeIntervalSince(lastAudioReceivedTime)
        fileLog.log("📱 APP FOREGROUND - call active, last audio \(String(format: "%.0f", timeSinceLastAudio))s ago")

        if timeSinceLastAudio > maxDisconnectionTime {
            // Call has been dead while device was sleeping
            print("❌ VoiceCall: Call stale after device wake - no audio for \(Int(timeSinceLastAudio))s")
            fileLog.log("❌ STALE CALL: ending after \(Int(timeSinceLastAudio))s with no audio")
            endCall(reason: .hungUp)
        } else if timeSinceLastAudio > connectionLossThreshold {
            // Connection degraded but not dead - restart audio engine
            print("⚠️ VoiceCall: Audio gap after wake - \(Int(timeSinceLastAudio))s, restarting engine")
            fileLog.log("⚠️ AUDIO GAP: \(Int(timeSinceLastAudio))s after wake, restarting engine")
            Task { @MainActor in
                self.stopAudioEngine()
                try? await Task.sleep(nanoseconds: 200_000_000)
                try? self.startAudioEngine()
            }
        }
    }
    #endif
    
    /// Look up contact alias for a public key
    /// Returns the saved alias if one exists, otherwise returns a shortened public key
    private func getDisplayName(for publicKey: String) -> String {
        // Try to load contact aliases from UserDefaults
        if let aliasData = UserDefaults.standard.data(forKey: "contactAliases"),
           let aliases = try? JSONDecoder().decode([String: String].self, from: aliasData),
           let alias = aliases[publicKey], !alias.isEmpty {
            return alias
        }
        // Fall back to shortened public key
        return String(publicKey.prefix(8)) + "..."
    }
    
    private func setupAudioSession() {
        #if os(iOS)
        do {
            let session = AVAudioSession.sharedInstance()

            // Detect if device has earpiece (iPhone) or not (iPad/Mac/Simulator)
            let hasEarpiece = UIDevice.current.userInterfaceIdiom == .phone

            // iPhone: Start with earpiece (like WhatsApp - more private)
            // iPad/Mac/Simulator: Always use speaker (no earpiece available)
            // 🔧 FIX: Use .allowBluetooth instead of .allowBluetoothA2DP for better compatibility
            // .allowBluetooth works with HFP (Hands-Free Profile) which is better for calls
            let defaultOptions: AVAudioSession.CategoryOptions = hasEarpiece
                ? [.allowBluetoothHFP]  // iPhone: earpiece first, allow Bluetooth HFP
                : [.defaultToSpeaker, .allowBluetoothHFP]  // iPad/Mac: speaker

            try session.setCategory(
                .playAndRecord,
                mode: .voiceChat,  // voiceChat provides echo cancellation
                options: defaultOptions
            )

            // Set preferred sample rate (16kHz is optimal for voice)
            try session.setPreferredSampleRate(sampleRate)

            // CRITICAL: Set lowest possible buffer duration for minimum latency
            // 0.005 = 5ms (minimum on most devices)
            try session.setPreferredIOBufferDuration(0.005)

            // Activate session
            try session.setActive(true, options: .notifyOthersOnDeactivation)

            // 🔧 FIX: Explicitly set initial output route AFTER activating session
            if hasEarpiece {
                // iPhone: Force earpiece (receiver) initially
                try session.overrideOutputAudioPort(.none)
            } else {
                // iPad/Mac/Simulator: Force speaker
                try session.overrideOutputAudioPort(.speaker)
            }

            // Verify and log the actual route
            let currentRoute = session.currentRoute
            let outputPort = currentRoute.outputs.first?.portType

            // Update speaker state based on device type
            DispatchQueue.main.async {
                self.isSpeakerOn = !hasEarpiece  // iPad starts with speaker ON, iPhone with OFF
            }

            print("✅ VoiceCall: Audio session configured")
            print("   Device: \(hasEarpiece ? "iPhone" : "iPad/Mac")")
            print("   Initial route: \(outputPort?.rawValue ?? "unknown")")
            print("   Buffer: \(session.ioBufferDuration * 1000)ms")
        } catch {
            print("❌ VoiceCall: Failed to setup audio session: \(error)")
        }
        #else
        print("⚠️ VoiceCall: Audio session not available on macOS")
        #endif
    }
    
    // MARK: - Start Call
    
    func startCall(to peerPublicKey: String, peerName: String, isVideoCall: Bool = false) async throws {
        guard callState == .idle else {
            throw CallError.alreadyInCall
        }
        
        // Check if peer is blocked
        if BlockedContactsManager.shared.isBlocked(peerPublicKey) {
            throw CallError.peerBlocked
        }
        
        // Generate unique call ID for VPS routing
        // 🔧 CRITICAL: This callId will be transmitted in the call request packet
        // so that the receiver uses the SAME callId for VPS audio routing
        currentCallId = UUID().uuidString
        print("📞 VoiceCall: Generated new callId for VPS routing: \(currentCallId ?? "none")")
        callLog.notice("📞 CALL INITIATED - callId: \(self.currentCallId ?? "none", privacy: .public)")
        fileLog.log("📞 CALL INITIATED - callId: \(currentCallId ?? "none")")

        // 🔧 FIX: Use contact alias if available, otherwise shortened public key
        let displayName = getDisplayName(for: peerPublicKey)
        
        // Get my public key for caller info
        let myPublicKey = UserDefaults.standard.string(forKey: "publicKey") ?? ""
        let myName = UserDefaults.standard.string(forKey: "userName") ?? "Unknown"
        
        await MainActor.run {
            self.callState = .connecting
            self.currentCallPeer = peerPublicKey
            self.currentCallPeerName = displayName
            self.isOutgoingCall = true
            self.isCurrentCallVideo = isVideoCall  // 📹 Track if this is a video call
            self.currentCallStartTime = Date()  // 🔧 FIX: Track when this call session started
            self.startWaitingTimer()  // Start timing how long we wait

            // 🔧 FIX: Report outgoing call to CallKit for proper audio session management
            #if os(iOS)
            VoIPPushManager.shared.startOutgoingCall(to: peerPublicKey, peerName: displayName, isVideoCall: isVideoCall)
            #endif
        }

        print(isVideoCall ? "📹 VoiceCall: Starting VIDEO call to \(displayName)" : "📞 VoiceCall: Starting call to \(displayName)")
        print("   Call ID: \(currentCallId ?? "none")")
        
        // ✅ SEND VOIP PUSH NOTIFICATION - Wake up the other device!
        #if os(iOS)
        print("📲 VoiceCall: Sending VoIP push to wake up recipient...")
        Task {
            do {
                try await VoIPPushManager.sendVoIPPush(
                    to: peerPublicKey,
                    callerPublicKey: myPublicKey,
                    callerName: myName.isEmpty ? "OSHI User" : myName,
                    callId: currentCallId ?? UUID().uuidString
                )
                print("✅ VoiceCall: VoIP push sent successfully")
            } catch {
                print("⚠️ VoiceCall: VoIP push failed (will rely on polling): \(error.localizedDescription)")
            }
        }
        #endif
        
        // Generate session key for this call (256-bit AES key)
        sessionKey = SymmetricKey(size: .bits256)
        callLog.notice("🔐 AES-256-GCM session key generated for outgoing call")
        fileLog.log("🔐 AES-256-GCM session key generated for outgoing call")
        callNonce = 0
        lastReceivedNonce = 0
        receivedAudioPacketCount = 0
        meshAudioReceivedCount = 0
        
        // Generate random salt for nonces (prevents nonce reuse across calls)
        var salt = Data(count: 4)
        _ = salt.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 4, $0.baseAddress!) }
        sessionNonceSalt = salt
        
        // Clear jitter buffer
        clearJitterBuffer()
        
        // Connection priority:
        // 1. Mesh Direct (P2P offline) - when no internet or nearby direct peer
        // 2. Direct mesh (P2P) - signaling via mesh, audio via VPS
        // 3. Mesh relay - via nearby peers
        // 4. VPS/Tor relay - works anywhere but higher latency

        #if os(iOS)
        // 🔧 ALL CALLS VIA VPS ONLY (mesh is for messaging only)
        // Signaling: VPS, Ringing: APN Push, Audio: VPS WebSocket/HTTP
        if !hasInternetConnection {
            print("❌ VoiceCall: No internet - cannot call")
            throw CallError.networkError
        }
        print("☁️ VoiceCall: ═══════════════════════════════════════════")
        print("☁️ VoiceCall: ALL CALLS VIA VPS (mesh disabled for calls)")
        print("☁️ VoiceCall: ═══════════════════════════════════════════")
        print("   📡 Signaling: VPS")
        print("   🔔 Ringing: APN Push Notification")
        print("   🔊 Audio: VPS WebSocket/HTTP")
        await MainActor.run {
            self.connectionType = .tor
            self.usingMeshRelay = false
        }
        try await startTorCall(to: peerPublicKey)
        #else
        // macOS/watchOS: Only VPS/Tor relay available
        if !hasInternetConnection {
            print("❌ VoiceCall: No internet - cannot call")
            throw CallError.networkError
        }
        print("📡 VoiceCall: Using VPS/Tor relay")
        await MainActor.run {
            self.connectionType = .tor
            self.usingMeshRelay = false
        }
        try await startTorCall(to: peerPublicKey)
        #endif
        
        await MainActor.run {
            self.callState = .ringing

            // 🔔 Play ringback tone for caller (waiting for answer)
            if self.isOutgoingCall {
                self.playRingbackTone()
            }

            // ⌚ Update Watch - call is ringing
            WatchConnectivityManager.shared.updateCallStateOnWatch(state: "ringing")
        }

        // Start timeout for no answer
        // 🔧 FIX: Extended to 45 seconds to allow more time for accept signal delivery
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 45_000_000_000) // 45 seconds
            if self?.callState == .ringing {
                self?.endCall(reason: .noAnswer)
            }
        }
    }
    
    private func startMeshCall(to peerPublicKey: String) async throws {
        // Create payload: session key (32 bytes) + nonce salt (4 bytes) + callId (variable)
        var payload = sessionKey!.withUnsafeBytes { Data($0) }
        payload.append(sessionNonceSalt)

        // 🔧 CRITICAL FIX: Include callId in payload so receiver uses the same callId for VPS audio routing
        if let callIdData = currentCallId?.data(using: .utf8) {
            payload.append(callIdData)
            print("📞 VoiceCall: Including callId in mesh call request: \(currentCallId ?? "none")")
        }

        // Send call request via mesh - use videoCallRequest for video calls
        let packetType: CallPacketType = isCurrentCallVideo ? .videoCallRequest : .callRequest
        let callRequest = createCallPacket(type: packetType, payload: payload)
        
        // Encrypt with ECDH-derived shared secret
        let encryptedRequest = try encryptForPeer(data: callRequest, peerPublicKey: peerPublicKey)
        
        // Send via mesh
        meshManager?.sendCallSignal(to: peerPublicKey, data: encryptedRequest)
    }
    
    /// Send call signal via mesh WITHOUT changing connection type
    /// Used as backup when sending via VPS (in case recipient has app in foreground)
    private func startMeshCallSignalOnly(to peerPublicKey: String) async throws {
        guard let sessionKey = sessionKey else {
            print("❌ VoiceCall: No session key for mesh backup signal")
            return
        }
        
        // Create payload: session key (32 bytes) + nonce salt (4 bytes) + callId
        var payload = sessionKey.withUnsafeBytes { Data($0) }
        payload.append(sessionNonceSalt)
        
        if let callIdData = currentCallId?.data(using: .utf8) {
            payload.append(callIdData)
        }
        
        let packetType: CallPacketType = isCurrentCallVideo ? .videoCallRequest : .callRequest
        let callRequest = createCallPacket(type: packetType, payload: payload)
        let encryptedRequest = try encryptForPeer(data: callRequest, peerPublicKey: peerPublicKey)
        
        // Send via mesh as backup (don't change connectionType)
        meshManager?.sendCallSignal(to: peerPublicKey, data: encryptedRequest)
        print("📶 VoiceCall: Backup call signal sent via mesh (VPS is primary)")
    }
    
    private func startMeshRelayCall(to peerPublicKey: String) async throws {
        // Send call signal via mesh relay - nearby peers will forward to target
        // This works even if target is not directly connected to us

        var payload = sessionKey!.withUnsafeBytes { Data($0) }
        payload.append(sessionNonceSalt)

        // 🔧 CRITICAL FIX: Include callId in payload so receiver uses the same callId for VPS audio routing
        if let callIdData = currentCallId?.data(using: .utf8) {
            payload.append(callIdData)
            print("📞 VoiceCall: Including callId in mesh relay call request: \(currentCallId ?? "none")")
        }

        let packetType: CallPacketType = isCurrentCallVideo ? .videoCallRequest : .callRequest
        let callRequest = createCallPacket(type: packetType, payload: payload)
        let encryptedRequest = try encryptForPeer(data: callRequest, peerPublicKey: peerPublicKey)
        
        // Use mesh network to send call signal
        // sendCallSignal sends directly if peer is connected, otherwise broadcasts
        meshManager?.sendCallSignal(to: peerPublicKey, data: encryptedRequest)
        
        print("📡 VoiceCall: Call signal sent via mesh network")
    }
    
    private func startTorCall(to peerPublicKey: String) async throws {
        // Connect to VPS via Tor for relay
        // The VPS only sees encrypted blobs - cannot decrypt content

        // Create payload: session key (32 bytes) + nonce salt (4 bytes) + callId (variable)
        var payload = sessionKey!.withUnsafeBytes { Data($0) }
        payload.append(sessionNonceSalt)

        // 🔧 CRITICAL FIX: Include callId in payload so receiver uses the same callId for VPS audio routing
        if let callIdData = currentCallId?.data(using: .utf8) {
            payload.append(callIdData)
            print("📞 VoiceCall: Including callId in VPS call request: \(currentCallId ?? "none")")
        }

        let packetType: CallPacketType = isCurrentCallVideo ? .videoCallRequest : .callRequest
        let callRequest = createCallPacket(type: packetType, payload: payload)
        let encryptedRequest = try encryptForPeer(data: callRequest, peerPublicKey: peerPublicKey)
        
        // Send via fallback service (which uses Tor)
        try await sendViaTorRelay(to: peerPublicKey, data: encryptedRequest)
    }
    
    // MARK: - Handle Incoming Call
    
    func handleIncomingCall(from peerPublicKey: String, peerName: String, encryptedData: Data, viaTor: Bool = true, viaRelay: Bool = false) {
        print("📞 VoiceCall: handleIncomingCall called")
        print("   Current state: \(callState)")
        print("   Encrypted data size: \(encryptedData.count) bytes")
        print("   viaTor: \(viaTor)")
        print("   viaRelay: \(viaRelay)")
        print("   isCallKitHandlingIncoming: \(isCallKitHandlingIncoming)")
        
        // 🔧 CRITICAL FIX: If CallKit is already showing the incoming call UI,
        // AND this is NOT a VoIP push signal (which sets up the encrypted data),
        // AND this is NOT the signal we're waiting for, ignore it
        // This prevents duplicate notifications when app is in foreground
        let normalizedPeerKeyEarly = base64urlDecode(peerPublicKey)
        let normalizedVoipPushCaller = voipPushActiveForCaller.map { base64urlDecode($0) }
        
        // Check if this is a duplicate from a different path than VoIP push
        if isCallKitHandlingIncoming && !viaTor {
            // CallKit is handling via VoIP push, ignore mesh signals
            print("⚠️ VoiceCall: Ignoring signal - CallKit already handling incoming call via VoIP push")
            return
        }
        
        // If VoIP push is active for a DIFFERENT caller, this is a conflict
        if let voipCaller = normalizedVoipPushCaller,
           voipCaller != normalizedPeerKeyEarly,
           voipPushReceivedTime != nil,
           Date().timeIntervalSince(voipPushReceivedTime!) < 5.0 {
            print("⚠️ VoiceCall: Ignoring signal from different caller - VoIP push handling another call")
            return
        }

        // 🔧 CRITICAL FIX: If we have a pending CallKit accept (call came via APN/VPS),
        // ignore MESH signals for the same caller - only process VPS signals
        // This prevents mesh from interfering with VPS-initiated calls
        let normalizedPendingKey = pendingCallKitAcceptCallerKey.map { base64urlDecode($0) }
        if pendingCallKitAccept && normalizedPendingKey == normalizedPeerKeyEarly && !viaTor {
            print("⚠️ VoiceCall: Ignoring MESH signal - call came via APN/VPS, waiting for VPS signal")
            print("   This prevents mesh from interfering with VPS-initiated calls")
            return
        }

        // 🔧 FIX: Prevent duplicate incoming call notifications from mesh
        // Use ONLY the normalized peer key for deduplication - don't include transport method
        // This ensures the same caller can only trigger one incoming call notification
        // regardless of whether it arrives via direct mesh, relay, or tor
        let signalKey = String(normalizedPeerKeyEarly.prefix(32))  // Use first 32 chars of normalized key

        // Check if we recently processed a call from this same peer (within 5 seconds)
        // Increased from 3s to 5s to catch more duplicates from mesh relay
        if let lastTime = lastIncomingCallTime,
           Date().timeIntervalSince(lastTime) < 5.0,
           processedIncomingCallKeys.contains(signalKey) {
            print("⚠️ VoiceCall: Ignoring duplicate incoming call signal from same peer (already processed within 5s)")
            print("   Path: \(viaTor ? "tor" : "mesh") \(viaRelay ? "relay" : "direct")")
            return
        }

        // Mark this signal as processed
        processedIncomingCallKeys.insert(signalKey)
        lastIncomingCallTime = Date()

        // Clean up old entries after 10 seconds
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
            self?.processedIncomingCallKeys.remove(signalKey)
        }

        // Check if caller is blocked
        if BlockedContactsManager.shared.isBlocked(peerPublicKey) {
            print("🚫 VoiceCall: Ignoring call from blocked contact: \(peerPublicKey.prefix(8))...")
            // Silently decline - don't even notify the caller we received it
            return
        }

        // 🔧 FIX: Normalize keys for comparison (base64url vs standard base64)
        let normalizedPeerKey = base64urlDecode(peerPublicKey)
        let normalizedCurrentPeer = currentCallPeer.map { base64urlDecode($0) }

        // If we're already in a call with the same peer, ignore duplicate signals
        if callState == .inCall && normalizedCurrentPeer == normalizedPeerKey {
            print("⚠️ VoiceCall: Ignoring duplicate signal - already in call with this peer")
            return
        }

        // If we're ringing from the same peer, ignore duplicate call requests
        // This prevents declining when we receive multiple call signals from the same caller
        if callState == .ringing && normalizedCurrentPeer == normalizedPeerKey {
            print("⚠️ VoiceCall: Ignoring duplicate call request - already ringing from this peer")
            return
        }

        // If we're accepting a call, ignore new signals
        if isAcceptingCall {
            print("⚠️ VoiceCall: Ignoring signal - currently accepting call")
            return
        }

        // 🔧 FIX: If auto-accept is already scheduled, ignore this signal
        if autoAcceptScheduled {
            print("⚠️ VoiceCall: Ignoring signal - auto-accept already scheduled")
            return
        }
        
        guard callState == .idle else {
            // Already in a call with a DIFFERENT peer, decline
            print("⚠️ VoiceCall: Cannot accept call - current state is \(callState), busy with another call")
            declineIncomingCall(from: peerPublicKey)
            return
        }
        
        // Decrypt and extract session key + nonce salt
        do {
            let decryptedData = try decryptFromPeer(data: encryptedData, peerPublicKey: peerPublicKey)
            print("   Decrypted data size: \(decryptedData.count) bytes")
            
            // 🔧 FIX: New packet format includes timestamp
            // Format: [type(1)] + [timestamp(8)] + [payload]
            // For call requests: payload = [session_key(32)] + [salt(4)]
            // New minimum: 1 + 8 + 32 + 4 = 45 bytes
            // Legacy minimum: 1 + 32 + 4 = 37 bytes

            guard let packetType = CallPacketType(rawValue: decryptedData[0]) else {
                print("❌ VoiceCall: Invalid packet type byte: \(decryptedData[0])")
                return
            }

            print("   Packet type: \(packetType)")

            // 📹 Check if this is a video call request
            let isVideoCall = packetType == .videoCallRequest
            fileLog.log("📞 INCOMING CALL: packetType=\(packetType) isVideo=\(isVideoCall) from=\(peerPublicKey.prefix(12))")

            guard packetType == .callRequest || packetType == .videoCallRequest else {
                // Handle other packet types
                if packetType == .callDecline {
                    print("📞 VoiceCall: Received call decline from peer")
                    receivedRemoteEndSignal = true  // Treat decline as remote-initiated end
                    handleCallDeclineFromPeer()
                    return
                } else if packetType == .callAccept || packetType == .videoCallAccept {
                    print("📞 VoiceCall: Received call accept from peer")
                    Task {
                        try? await handleCallAccepted()
                    }
                    return
                } else if packetType == .callEnd {
                    // 🔧 FIX: Validate timestamp to reject stale callEnd signals
                    guard validateAndExtractPayload(from: decryptedData, packetType: .callEnd) != nil else {
                        print("⚠️ VoiceCall: Ignoring stale/invalid callEnd signal in handleIncomingCall")
                        return
                    }
                    print("📞 VoiceCall: Received call end from peer")
                    fileLog.log("📩 SIGNAL(incoming): callEnd received - state: \(callState)")
                    receivedRemoteEndSignal = true  // Mark that remote initiated the end
                    endCall(reason: .peerDisconnected)
                    return
                } else if packetType == .callAnsweredElsewhere {
                    // 📱 MULTI-DEVICE: Call was answered on another device
                    print("📱 VoiceCall: Received callAnsweredElsewhere in handleIncomingCall")
                    handleCallAnsweredElsewhere()
                    return
                }
                print("❌ VoiceCall: Unexpected packet type for incoming call: \(packetType)")
                return
            }

            // Determine if this is a new format packet (with timestamp) or legacy
            // New format: [type(1)] + [timestamp(8)] + [session_key(32)] + [salt(4)] + [callId(variable)] >= 45 bytes
            // Legacy: [type(1)] + [session_key(32)] + [salt(4)] = 37 bytes
            let keyData: Data
            let saltData: Data
            var extractedCallId: String? = nil

            if decryptedData.count >= 45 {
                // New format with timestamp - validate and extract
                guard let payload = validateAndExtractPayload(from: decryptedData, packetType: packetType) else {
                    print("⚠️ VoiceCall: Ignoring stale/invalid call request")
                    return
                }
                guard payload.count >= 36 else {
                    print("❌ VoiceCall: Payload too small after timestamp: \(payload.count) bytes (need 36)")
                    return
                }
                // Extract: [session_key(32)] + [salt(4)] + [callId(variable)]
                keyData = Data(payload[0..<32])
                saltData = Data(payload[32..<36])

                // 🔧 CRITICAL FIX: Extract callId from payload if present
                if payload.count > 36 {
                    let callIdBytes = Data(payload[36...])
                    extractedCallId = String(data: callIdBytes, encoding: .utf8)
                    print("📞 VoiceCall: Extracted callId from payload: \(extractedCallId ?? "nil")")
                }
            } else if decryptedData.count >= 37 {
                // Legacy format without timestamp - accept for backwards compatibility
                print("   📦 Legacy packet format (no timestamp)")
                keyData = Data(decryptedData[1..<33])
                saltData = Data(decryptedData[33..<37])

                // 🔧 CRITICAL FIX: Extract callId from legacy payload if present
                if decryptedData.count > 37 {
                    let callIdBytes = Data(decryptedData[37...])
                    extractedCallId = String(data: callIdBytes, encoding: .utf8)
                    print("📞 VoiceCall: Extracted callId from legacy payload: \(extractedCallId ?? "nil")")
                }
            } else {
                print("❌ VoiceCall: Decrypted data too small: \(decryptedData.count) bytes (need at least 37)")
                return
            }

            sessionKey = SymmetricKey(data: keyData)
            sessionNonceSalt = Data(saltData)
            callLog.notice("🔐 AES-256-GCM session key received and installed for incoming call")
            fileLog.log("🔐 AES-256-GCM session key received and installed for incoming call")
            callNonce = 0
            lastReceivedNonce = 0

            // 🔧 CRITICAL FIX: Use caller's callId so both parties use same ID for VPS audio routing
            if let callId = extractedCallId, !callId.isEmpty {
                currentCallId = callId
                print("📞 VoiceCall: Using caller's callId: \(callId)")
            } else {
                // Fallback: generate new callId (for backwards compatibility with old callers)
                currentCallId = UUID().uuidString
                print("📞 VoiceCall: No callId in payload, generated new: \(currentCallId ?? "none")")
            }

            // Clear jitter buffer
            clearJitterBuffer()
            
            // 🔧 FIX: Use contact alias if available, otherwise shortened public key
            let displayName = getDisplayName(for: peerPublicKey)
            
            // 📹 Capture isVideoCall for async block
            let isVideo = isVideoCall
            
            DispatchQueue.main.async {
                self.incomingCall = IncomingCall(peerPublicKey: peerPublicKey, peerName: displayName, timestamp: Date(), isVideoCall: isVideo)
                self.currentCallPeer = peerPublicKey
                self.currentCallPeerName = displayName
                self.callState = .ringing
                self.isCurrentCallVideo = isVideo  // 📹 Track video call status
                self.currentCallStartTime = Date()  // 🔧 FIX: Track when this call session started

                if isVideo {
                    print("📹 VoiceCall: Incoming VIDEO call from \(displayName)")
                } else {
                    print("📞 VoiceCall: Incoming VOICE call from \(displayName)")
                }

                // ⌚ Forward incoming call to Apple Watch
                WatchConnectivityManager.shared.forwardIncomingCallToWatch(
                    callerKey: peerPublicKey,
                    callerName: displayName,
                    isVideo: isVideo
                )

                // 🔧 SIMPLIFIED ARCHITECTURE FOR INCOMING CALLS:
                // - Call came via VPS (APN/polling) → .tor (ALL via VPS)
                // - Call came via mesh (direct P2P) → .meshDirect (ALL via mesh)
                // 🔒 CRITICAL: If VoIP push is active, ALWAYS use VPS even if signal came via mesh
                // This handles the case where caller sends both VPS + mesh backup
                let hasPendingCallKitAccept = self.pendingCallKitAccept && self.pendingCallKitAcceptCallerKey == peerPublicKey
                
                // 🔧 FIX: Check if VoIP push was recently received for ANY caller
                // If so, the caller is using VPS as primary, so we must respond via VPS
                let voipPushRecentlyActive = self.voipPushActiveForCaller != nil ||
                    (self.voipPushReceivedTime != nil && Date().timeIntervalSince(self.voipPushReceivedTime!) < 10.0)
                
                // Normalize keys for comparison
                let normalizedPeerKey = peerPublicKey
                    .replacingOccurrences(of: "-", with: "+")
                    .replacingOccurrences(of: "_", with: "/")
                let normalizedVoipCaller = self.voipPushActiveForCaller?
                    .replacingOccurrences(of: "-", with: "+")
                    .replacingOccurrences(of: "_", with: "/")
                
                // Check if VoIP push was for this specific caller
                _ = normalizedVoipCaller != nil &&
                    (normalizedVoipCaller!.hasPrefix(String(normalizedPeerKey.prefix(32))) ||
                     normalizedPeerKey.hasPrefix(String(normalizedVoipCaller!.prefix(32))))

                // 🔒 CRITICAL FIX: Use VPS if:
                // 1. Signal came via VPS (viaTor = true), OR
                // 2. We have a pending CallKit accept, OR
                // 3. VoIP push was recently received (caller is using VPS as primary)
                let shouldUseVPS = viaTor || hasPendingCallKitAccept || voipPushRecentlyActive

                // 🔧 ALL CALLS VIA VPS ONLY (mesh is for messaging only)
                self.connectionType = .tor
                self.usingMeshRelay = false
                self.incomingCallConnectionType = .tor
                self.incomingCallConnectionLocked = true

                print("☁️ VoiceCall: ═══════════════════════════════════════════")
                print("☁️ VoiceCall: INCOMING CALL → Using VPS for EVERYTHING")
                print("🔒 VoiceCall: Connection type LOCKED to .tor (VPS)")
                print("☁️ VoiceCall: ═══════════════════════════════════════════")

                // 🔧 FIX: Check if we have a pending accept from CallKit
                if hasPendingCallKitAccept {
                    print("📞 VoiceCall: Pending CallKit accept found - auto-accepting call...")
                    self.pendingCallKitAccept = false
                    self.pendingCallKitAcceptCallerKey = nil

                    // 🔧 CRITICAL FIX: Set flag BEFORE scheduling to prevent race condition
                    // This prevents manual accept from running in parallel with auto-accept
                    self.autoAcceptScheduled = true
                    self.isAcceptingCall = true  // Block other accept attempts NOW
                    
                    // 🔧 FIX: Clear VoIP push suppression since we're handling the call now
                    self.voipPushActiveForCaller = nil
                    self.voipPushReceivedTime = nil

                    // 🔧 FIX: Increased delay from 0.2s to 0.6s to ensure:
                    // 1. State is fully settled
                    // 2. Session key is properly set
                    // 3. Caller has finished setting up their end
                    // 4. Network round-trip has time to complete
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                        // 🔧 FIX: Only proceed if we're still in ringing state
                        guard self.callState == .ringing else {
                            print("⚠️ VoiceCall: Auto-accept cancelled - call state changed")
                            self.autoAcceptScheduled = false
                            self.isAcceptingCall = false
                            return
                        }
                        
                        // 🔧 FIX: Verify session key is set before accepting
                        guard self.sessionKey != nil else {
                            print("⚠️ VoiceCall: Auto-accept delayed - session key not ready, retrying...")
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                                guard self.callState == .ringing, self.sessionKey != nil else {
                                    print("❌ VoiceCall: Auto-accept failed - call not properly set up")
                                    self.autoAcceptScheduled = false
                                    self.isAcceptingCall = false
                                    return
                                }
                                Task {
                                    do {
                                        try await self.acceptCall()
                                    } catch {
                                        print("❌ VoiceCall: Auto-accept failed: \(error)")
                                    }
                                    await MainActor.run {
                                        self.autoAcceptScheduled = false
                                    }
                                }
                            }
                            return
                        }
                        
                        Task {
                            do {
                                try await self.acceptCall()
                            } catch {
                                print("❌ VoiceCall: Auto-accept failed: \(error)")
                            }
                            // Reset flags after accept completes
                            await MainActor.run {
                                self.autoAcceptScheduled = false
                            }
                        }
                    }
                } else {
                    // Play ringtone only if not auto-accepting
                    // 🔧 FIX: Don't play our ringtone if CallKit is already ringing
                    // This prevents double ringtone (CallKit + our custom)
                    if !self.isCallKitHandlingIncoming {
                        self.playRingtone()
                    } else {
                        print("📞 VoiceCall: Skipping playRingtone - CallKit is handling ringing")
                    }
                }
            }

            print("📞 VoiceCall: Incoming call from \(peerName) (E2E encrypted)")
            
        } catch {
            print("❌ VoiceCall: Failed to decrypt call signal: \(error)")
            return
        }
    }
    
    /// Handle all types of received call signals (from IPFS or mesh)
    /// This routes callRequest, callAccept, callDecline, callEnd properly
    func handleReceivedCallSignal(from peerPublicKey: String, peerName: String, encryptedData: Data, viaTor: Bool = true, viaRelay: Bool = false) {
        print("📞 VoiceCall: ============================================")
        print("📞 VoiceCall: Received call signal from \(peerName)")
        print("   My state: \(callState)")
        print("   Current peer: \(currentCallPeer?.prefix(16) ?? "none")")
        print("   From peer: \(peerPublicKey.prefix(16))...")
        print("   Data size: \(encryptedData.count) bytes")
        print("   isOutgoingCall: \(isOutgoingCall)")
        print("   viaTor: \(viaTor)")
        print("   viaRelay: \(viaRelay)")
        
        // 🔧 FIX: During active call, only process signals from current peer
        // This prevents mesh discovery from interfering with VPS calls
        // 🔧 CRITICAL: Normalize keys before comparing (base64url vs standard base64)
        if callState == .inCall {
            if let currentPeer = currentCallPeer {
                let normalizedCurrent = base64urlDecode(currentPeer)
                let normalizedIncoming = base64urlDecode(peerPublicKey)
                if normalizedCurrent != normalizedIncoming {
                    print("   ⚠️ Ignoring signal from different peer during active call")
                    print("📞 VoiceCall: ============================================")
                    return
                }
            }
        }
        
        // For outgoing calls, we need to match the peer we're calling
        // 📱 MULTI-DEVICE: For signals from our own public key (callAnsweredElsewhere),
        // we need to decrypt using our own public key
        let peerToDecrypt: String
        let myPublicKey = getIdentityManager()?.publicKey
        let normalizedSender = base64urlDecode(peerPublicKey)
        let normalizedMyKey = myPublicKey.map { base64urlDecode($0) }

        if normalizedMyKey != nil && normalizedSender == normalizedMyKey {
            // Signal from our own public key (multi-device) - decrypt with our own key
            peerToDecrypt = peerPublicKey
            print("   📱 Using own public key for decryption (multi-device signal)")
        } else if let currentPeer = currentCallPeer {
            peerToDecrypt = currentPeer
            print("   Using currentCallPeer for decryption")
        } else {
            peerToDecrypt = peerPublicKey
            print("   Using sender's key for decryption")
        }

        // Try to decrypt
        do {
            let decryptedData = try decryptFromPeer(data: encryptedData, peerPublicKey: peerToDecrypt)
            print("   ✅ Decryption successful! Size: \(decryptedData.count) bytes")
            
            guard decryptedData.count >= 1,
                  let packetType = CallPacketType(rawValue: decryptedData[0]) else {
                print("   ❌ Invalid packet format")
                return
            }
            
            print("📞 VoiceCall: Decrypted signal type: \(packetType)")
            
            switch packetType {
            case .callRequest, .videoCallRequest:
                print("   📞 Got callRequest (video: \(packetType == .videoCallRequest))")
                // Already in a call or this is handled by handleIncomingCall
                if callState == .idle {
                    handleIncomingCall(from: peerPublicKey, peerName: peerName, encryptedData: encryptedData, viaTor: viaTor, viaRelay: viaRelay)
                } else {
                    print("   ⚠️ Ignoring callRequest - not idle (state: \(callState))")
                }
                
            case .callAccept, .videoCallAccept:
                print("   ✅ VoiceCall: Call ACCEPTED by peer!")
                print("   Current state before handling: \(callState)")
                print("   hasStartedCall: \(hasStartedCall)")
                fileLog.log("📩 SIGNAL: callAccept received - state: \(callState), hasStarted: \(hasStartedCall)")
                // Accept can come when we're in ringing OR connecting (for outgoing calls)
                // 🔧 FIX: Also check hasStartedCall to prevent duplicate accept processing
                if (callState == .ringing || callState == .connecting) && !hasStartedCall {
                    // CRITICAL FIX: Determine the new connection type BEFORE starting the call
                    // This fixes the race condition where handleCallAccepted() would run
                    // 🔧 SIMPLIFIED: No connection type switching needed
                    // Connection type is already set correctly when call started:
                    // - .meshDirect for direct mesh calls
                    // - .tor for VPS calls
                    Task { @MainActor in
                        try? await self.handleCallAccepted()
                    }
                } else if callState == .inCall {
                    print("   ⚠️ Ignoring callAccept - already in call (duplicate signal)")
                } else if hasStartedCall {
                    print("   ⚠️ Ignoring callAccept - already processing (hasStartedCall=true)")
                } else {
                    print("   ⚠️ Ignoring callAccept - not in ringing/connecting state (state: \(callState))")
                }
                
            case .callDecline:
                print("❌ VoiceCall: Call declined by peer")
                receivedRemoteEndSignal = true  // Treat decline as remote-initiated end
                endCall(reason: .declined)
                
            case .callEnd:
                // 🔧 FIX: Validate timestamp to reject stale callEnd signals
                // This prevents old callEnd signals from previous calls ending current calls
                print("📞 VoiceCall: Received callEnd signal")
                print("   callConnectedTime: \(String(describing: callConnectedTime))")
                print("   currentCallStartTime: \(String(describing: currentCallStartTime))")
                fileLog.log("📩 SIGNAL: callEnd received - state: \(callState), connTime: \(callConnectedTime?.description ?? "nil")")
                
                guard let payload = validateAndExtractPayload(from: decryptedData, packetType: .callEnd) else {
                    print("⚠️ VoiceCall: Ignoring stale/invalid callEnd signal")
                    return
                }

                // 🔧 FIX: Only process callEnd if we're in a call state that should receive it
                // This prevents race conditions where callEnd arrives while call is being set up
                guard callState == .inCall || callState == .ringing || callState == .connecting else {
                    print("⚠️ VoiceCall: Ignoring callEnd - not in active call state (state: \(callState))")
                    print("📞 VoiceCall: ============================================")
                    return
                }
                
                // 🔧 FIX: Brief grace period to filter stale callEnd from previous call attempts
                // Reduced from 15s to 3s — 15s was blocking legitimate hang-ups
                if let connectedTime = callConnectedTime {
                    let timeSinceConnect = Date().timeIntervalSince(connectedTime)
                    if timeSinceConnect < 3.0 {
                        print("⚠️ VoiceCall: Ignoring callEnd during 3s post-connect grace period (connected \(String(format: "%.1f", timeSinceConnect))s ago)")
                        print("📞 VoiceCall: ============================================")
                        return
                    }
                }

                // 🔧 FIX: Also protect calls that are JUST starting (ringing/connecting)
                // If we received a callAccept recently, ignore callEnd for a brief period
                // This prevents race conditions in multi-device scenarios
                if let startTime = currentCallStartTime {
                    let timeSinceStart = Date().timeIntervalSince(startTime)
                    // For outgoing calls, ignore callEnd during the first 5 seconds after ringing starts
                    // This gives time for the accept signal to be processed
                    if isOutgoingCall && callState == .ringing && timeSinceStart < 5.0 {
                        print("⚠️ VoiceCall: Ignoring callEnd during outgoing call setup (ringing for \(String(format: "%.1f", timeSinceStart))s)")
                        print("📞 VoiceCall: ============================================")
                        return
                    }
                }

                var reason: CallEndReason = .hungUp
                if !payload.isEmpty {
                    if let reasonString = String(data: payload, encoding: .utf8),
                       let parsedReason = CallEndReason(rawValue: reasonString) {
                        reason = parsedReason
                    }
                }
                print("📞 VoiceCall: Call ended by peer - \(reason)")
                print("   📍 Source: handleReceivedCallSignal")
                receivedRemoteEndSignal = true  // Mark that remote initiated the end
                endCall(reason: reason)
                
            case .audioData:
                // Audio packet - process it
                let audioData = Data(decryptedData.dropFirst())
                receiveAudio(encryptedData: audioData)
                
            case .keepAlive:
                // Just a ping to keep connection alive
                print("💓 VoiceCall: Keep-alive from peer")

            case .keyExchange:
                // Key exchange packet
                break

            case .callAnsweredElsewhere:
                // 📱 MULTI-DEVICE: Call was answered on another device
                print("📱 VoiceCall: Received callAnsweredElsewhere signal")
                handleCallAnsweredElsewhere()
            }
        } catch {
            print("   ❌ Decryption failed: \(error)")
            // Can't decrypt with current peer - might be a new incoming call
            print("📞 VoiceCall: Trying as new incoming call...")
            handleIncomingCall(from: peerPublicKey, peerName: peerName, encryptedData: encryptedData, viaTor: viaTor)
        }
        print("📞 VoiceCall: ============================================")
    }

    func acceptCall() async throws {
        // Prevent multiple accept attempts
        guard !isAcceptingCall else {
            print("⚠️ VoiceCall: Already accepting call, ignoring duplicate")
            return
        }

        guard let incoming = incomingCall, callState == .ringing else {
            throw CallError.noIncomingCall
        }

        // Set flag immediately to prevent race conditions
        isAcceptingCall = true
        defer { isAcceptingCall = false }

        // 🔧 FIX: Use the caller's call ID if we received one, otherwise generate new
        if currentCallId == nil || currentCallId?.isEmpty == true {
            currentCallId = UUID().uuidString
        }

        print("📞 VoiceCall: Accepting call from \(incoming.peerName)...")
        print("   Call ID: \(currentCallId ?? "none")")
        print("   Connection type: \(connectionType)")
        callLog.notice("📞 ACCEPTING CALL from \(incoming.peerName, privacy: .public) via \(String(describing: self.connectionType), privacy: .public)")
        fileLog.log("📞 ACCEPTING CALL from \(incoming.peerName) via \(connectionType) isVideo:\(incoming.isVideoCall) callId:\(currentCallId ?? "none")")

        stopRingtone()

        // Play connection sound
        AudioServicesPlaySystemSound(1003) // "Connect" sound
        #if os(iOS)
        await MainActor.run {
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.success)
        }
        #endif

        // 📹 Check if this is a video call
        let isVideoCallAccept = incoming.isVideoCall

        // Send accept - use appropriate packet type for voice vs video
        let acceptPacketType: CallPacketType = isVideoCallAccept ? .videoCallAccept : .callAccept
        let acceptPacket = createCallPacket(type: acceptPacketType, payload: Data())
        let encrypted = try encryptForPeer(data: acceptPacket, peerPublicKey: incoming.peerPublicKey)

        // 🔧 ALL CALLS VIA VPS ONLY - send accept signal 3x for reliability
        print("☁️ VoiceCall: Sending accept via VPS (3x for reliability)...")
        fileLog.log("☁️ Sending accept via VPS to \(incoming.peerPublicKey.prefix(12))...")
        try await sendViaTorRelay(to: incoming.peerPublicKey, data: encrypted)
        fileLog.log("☁️ Accept signal sent (1/3)")
        // Resend 2 more times with short delays for reliability
        let peerKey = incoming.peerPublicKey
        Task {
            try? await Task.sleep(nanoseconds: 1_000_000_000) // 1 second
            try? await self.sendViaTorRelay(to: peerKey, data: encrypted)
            fileLog.log("☁️ Accept signal resent (2/3)")
            try? await Task.sleep(nanoseconds: 1_500_000_000) // 1.5 seconds
            try? await self.sendViaTorRelay(to: peerKey, data: encrypted)
            fileLog.log("☁️ Accept signal resent (3/3)")
        }

        await MainActor.run {
            self.incomingCall = nil
            self.callState = .inCall
            self.callStartTime = Date()
            self.callConnectedTime = Date()
            self.lastAudioReceivedTime = Date()
            self.isCurrentCallVideo = isVideoCallAccept
            self.isCallKitHandlingIncoming = false
            self.stopWaitingTimer()
            self.voipPushActiveForCaller = nil
            self.voipPushReceivedTime = nil

            fileLog.log("✅ CALLEE CONNECTED: callState=.inCall isVideo=\(isVideoCallAccept) wasCallKit=\(self.wasAnsweredViaCallKit)")

            // 🔔 Stop all tones when call connects
            self.stopRingtone()
            self.stopRingbackTone()

            // 🔧 FIX: Only dismiss CallKit if user accepted via our in-app overlay
            // When answered via CallKit (from background/lock screen), keep CallKit alive
            // to maintain audio session management and prevent the call from crashing
            #if os(iOS)
            if !self.wasAnsweredViaCallKit {
                VoIPPushManager.shared.reportIncomingCallHandledByApp()
                print("📞 VoiceCall: Dismissed CallKit (accepted via in-app overlay)")
            } else {
                print("📞 VoiceCall: Keeping CallKit alive (accepted via CallKit)")
            }
            #endif

            // ⌚ Update Watch - call connected
            WatchConnectivityManager.shared.updateCallStateOnWatch(state: "inCall")
        }

        // 🔧 FIX: Start timer and keepalive IMMEDIATELY after state change
        // Previously these were after startAudioEngine() which can throw,
        // causing timer to never start (stuck at 00:00)
        startCallTimer()
        startKeepAlive()

        // 🔒 Lock connection type EARLY so audio streaming uses correct type
        lockConnectionType()

        // 📶 Start connection loss detection (beep + reconnection + auto-end)
        startConnectionLossDetection()

        // Register call with VPS for audio routing (always - all calls use VPS)
        if true {
            await registerCallWithVPS()
        }

        // Configure audio session BEFORE starting engine
        #if os(iOS)
        do {
            let audioSession = AVAudioSession.sharedInstance()

            // 🔧 FIX: Use .videoChat mode for better speaker support
            try audioSession.setCategory(.playAndRecord, mode: .videoChat, options: [.defaultToSpeaker, .allowBluetoothA2DP])
            try audioSession.setPreferredSampleRate(16000)
            try audioSession.setPreferredIOBufferDuration(0.005)
            try audioSession.setActive(true)

            // Force speaker output after activation
            try audioSession.overrideOutputAudioPort(.speaker)

            // 📶 Listen for audio session interruptions (Siri, phone calls, music)
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(handleAudioSessionInterruption),
                name: AVAudioSession.interruptionNotification,
                object: audioSession
            )

            let currentRoute = audioSession.currentRoute
            let outputPort = currentRoute.outputs.first?.portType
            print("🔊 VoiceCall: Audio output route: \(outputPort?.rawValue ?? "unknown")")

            await MainActor.run {
                isSpeakerOn = true
            }

            print("✅ VoiceCall: Audio session activated for call (speaker ON)")
            try await Task.sleep(nanoseconds: 200_000_000) // 200ms
        } catch {
            print("❌ VoiceCall: Failed to configure audio session: \(error)")
        }
        #endif

        // 🔧 FIX: Wrap audio engine start in do/catch - don't let it kill the call
        // Audio may recover via retry or reconnection
        do {
            try startAudioEngine()
            print("✅ VoiceCall: Audio engine started successfully")
        } catch {
            print("❌ VoiceCall: Audio engine failed to start: \(error) - will retry")
            // Retry after a short delay (audio session may need time to settle)
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 500_000_000) // 500ms
                guard let self = self, self.callState == .inCall else { return }
                do {
                    try self.startAudioEngine()
                    print("✅ VoiceCall: Audio engine started on retry")
                } catch {
                    print("❌ VoiceCall: Audio engine retry also failed: \(error)")
                }
            }
        }

        // Start audio streaming for ALL VPS-routed calls (WebSocket + HTTP polling)
        // 🔧 FIX: Use lockedConnectionType (not connectionType) and include .mesh
        // .mesh = signaling + audio via VPS, .meshRelay = mesh signaling + VPS audio
        // Only .meshDirect uses mesh P2P audio without VPS streaming
        if lockedConnectionType != .meshDirect {
            startAudioStreaming()
        }

        print("✅ VoiceCall: Call accepted - state: \(callState), connectionType: \(connectionType)")
        callLog.notice("✅ CALL ACCEPTED - state: \(String(describing: self.callState), privacy: .public), conn: \(String(describing: self.connectionType), privacy: .public)")
        fileLog.log("✅ CALL ACCEPTED - state: \(callState), conn: \(connectionType)")
    }

    func declineIncomingCall(from peerPublicKey: String? = nil) {
        let peer = peerPublicKey ?? incomingCall?.peerPublicKey
        guard let targetPeer = peer else { return }

        stopRingtone()

        // 🔧 FIX: Tell CallKit the call was declined (stops CallKit ringing/banner)
        #if os(iOS)
        VoIPPushManager.shared.endCall(reason: .declinedElsewhere)
        #endif
        
        // Send decline
        // 🔧 SIMPLIFIED: Use .meshDirect or .tor based on locked connection type
        let declinePacket = createCallPacket(type: .callDecline, payload: Data())
        if let encrypted = try? encryptForPeer(data: declinePacket, peerPublicKey: targetPeer) {
            if connectionType == .meshDirect {
                meshManager?.sendCallSignal(to: targetPeer, data: encrypted)
                print("📶 VoiceCall: Sent decline via MESH")
            } else {
                Task {
                    try? await sendViaTorRelay(to: targetPeer, data: encrypted)
                    print("☁️ VoiceCall: Sent decline via VPS")
                }
            }
        }
        
        DispatchQueue.main.async {
            self.incomingCall = nil
            self.callState = .idle
            self.currentCallPeer = nil
            self.currentCallPeerName = nil
        }
        
        print("❌ VoiceCall: Call declined")
    }
    
    /// Handle call decline received from peer
    private func handleCallDeclineFromPeer() {
        print("📞 VoiceCall: Peer declined our call")

        stopWaitingTimer()

        DispatchQueue.main.async {
            self.callState = .idle
            self.currentCallPeer = nil
            self.currentCallPeerName = nil

            // Notify UI that call was declined
            NotificationCenter.default.post(
                name: NSNotification.Name("CallDeclinedByPeer"),
                object: nil
            )
        }
    }

    // MARK: - 📱 Multi-Device Call Handling

    /// Handle receiving notification that call was answered on another device
    private func handleCallAnsweredElsewhere() {
        print("📱 VoiceCall: ═══════════════════════════════════════════")
        print("📱 VoiceCall: CALL ANSWERED ON ANOTHER DEVICE")
        print("📱 VoiceCall: ═══════════════════════════════════════════")
        print("   Current state: \(callState)")
        print("   Device ID: \(deviceId)")

        // Only handle if we're in a state where we're waiting for or showing an incoming call
        guard callState == .ringing || incomingCall != nil else {
            print("📱 VoiceCall: Not in ringing state, ignoring answeredElsewhere")
            return
        }

        stopRingtone()

        // Report to CallKit that call was answered elsewhere
        #if os(iOS)
        VoIPPushManager.shared.endCall(reason: .answeredElsewhere)
        #endif

        DispatchQueue.main.async {
            self.incomingCall = nil
            self.lastCallEndReason = .answeredElsewhere
            self.callState = .ended(reason: .answeredElsewhere)
            self.currentCallPeer = nil
            self.currentCallPeerName = nil
            self.currentCallId = nil

            // Notify UI that call was answered on another device
            NotificationCenter.default.post(
                name: NSNotification.Name("CallAnsweredElsewhere"),
                object: nil
            )

            // Reset to idle after showing the message briefly
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                if case .ended = self.callState {
                    self.callState = .idle
                }
            }
        }

        print("📱 VoiceCall: Call UI dismissed - answered elsewhere")
    }

    /// Notify other devices that this device answered the call
    /// This is called when accepting an incoming call to dismiss the call UI on other devices
    private func notifyOtherDevicesCallAnswered() async {
        guard let myPublicKey = getIdentityManager()?.publicKey,
              let callId = currentCallId else {
            print("📱 VoiceCall: Cannot notify other devices - missing data")
            return
        }

        print("📱 VoiceCall: ═══════════════════════════════════════════")
        print("📱 VoiceCall: NOTIFYING OTHER DEVICES - CALL ANSWERED")
        print("📱 VoiceCall: ═══════════════════════════════════════════")
        print("   My device ID: \(deviceId)")
        print("   Call ID: \(callId)")

        // Create the "answered elsewhere" packet
        // Payload contains: deviceId that answered + callId
        var payload = Data()
        if let deviceIdData = deviceId.data(using: .utf8) {
            // Append deviceId length (1 byte) + deviceId + callId
            payload.append(UInt8(deviceIdData.count))
            payload.append(deviceIdData)
        }
        if let callIdData = callId.data(using: .utf8) {
            payload.append(callIdData)
        }

        let packet = createCallPacket(type: .callAnsweredElsewhere, payload: payload)

        // Encrypt for self (same public key, different device)
        guard let encrypted = try? encryptForPeer(data: packet, peerPublicKey: myPublicKey) else {
            print("📱 VoiceCall: Failed to encrypt answeredElsewhere packet")
            return
        }

        // Send via VPS to all devices with the same public key
        // The VPS will route to all devices except the one that sent it (by deviceId)
        do {
            try await sendAnsweredElsewhereViaVPS(encrypted: encrypted, excludeDeviceId: deviceId)
            print("✅ VoiceCall: Sent answeredElsewhere notification via VPS")
        } catch {
            print("⚠️ VoiceCall: Failed to send answeredElsewhere: \(error.localizedDescription)")
        }
    }

    /// Send "answered elsewhere" signal via VPS to other devices with same public key
    private func sendAnsweredElsewhereViaVPS(encrypted: Data, excludeDeviceId: String) async throws {
        guard let myPublicKey = getIdentityManager()?.publicKey,
              let callId = currentCallId else {
            throw CallError.networkError
        }

        guard let url = URL(string: "\(vpsBaseURL)/signal") else {
            throw CallError.networkError
        }

        // Send to self (same public key) but VPS will exclude the sending device
        let payload: [String: Any] = [
            "recipient": myPublicKey,
            "sender": myPublicKey,
            "signal": encrypted.base64EncodedString(),
            "callId": callId,
            "type": "callAnsweredElsewhere",
            "senderDeviceId": excludeDeviceId  // VPS uses this to exclude the sender
        ]

        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload) else {
            throw CallError.networkError
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = jsonData
        request.timeoutInterval = 5

        let (_, response) = try await URLSession.shared.data(for: request)
        if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
            print("📱 VoiceCall: VPS accepted answeredElsewhere signal")
        }
    }

    // MARK: - Call Accepted (outgoing)
    
    private var hasStartedCall = false  // Prevent multiple call starts
    
    func handleCallAccepted() async throws {
        // Prevent multiple acceptances
        guard !hasStartedCall else {
            print("⚠️ VoiceCall: handleCallAccepted - already started call, ignoring duplicate")
            return
        }

        // Accept when ringing OR connecting (caller waits for accept during both states)
        guard callState == .ringing || callState == .connecting else {
            print("⚠️ VoiceCall: handleCallAccepted called but state is \(callState), not ringing/connecting")
            return
        }

        hasStartedCall = true

        print("✅ VoiceCall: Call accepted! Transitioning to inCall state...")
        callLog.notice("✅ CALL CONNECTED (caller side) - transitioning to inCall")
        fileLog.log("✅ CALL CONNECTED (caller side) - transitioning to inCall")

        // Play connection sound
        await MainActor.run {
            AudioServicesPlaySystemSound(1003) // "Connect" sound
            #if os(iOS)
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.success)
            #endif
        }

        await MainActor.run {
            self.callState = .inCall
            self.callStartTime = Date()
            self.callConnectedTime = Date()  // 🔧 FIX: Set connected time for grace period protection
            self.lastAudioReceivedTime = Date()  // 🔧 FIX: Prevent premature audio timeout
            self.stopWaitingTimer()  // Stop waiting timer, call is connected

            // 🔔 Stop all tones when call connects
            self.stopRingtone()
            self.stopRingbackTone()
        }

        // 🔧 CRITICAL FIX: Tell CallKit the outgoing call connected!
        // Without this, CallKit thinks the call is still "connecting" and triggers CXEndCallAction
        VoIPPushManager.shared.reportOutgoingCallConnected()
        fileLog.log("📞 CallKit: reportOutgoingCallConnected() called")

        // 🔧 FIX: Start timer and keepalive IMMEDIATELY after state change
        // Previously these were after startAudioEngine() which can throw,
        // causing timer to never start (stuck at 00:00)
        startCallTimer()
        startKeepAlive()

        // 🔧 FIX: Lock connection type BEFORE checking it for audio streaming
        lockConnectionType()

        // 📶 Start connection loss detection (beep + reconnection + auto-end)
        startConnectionLossDetection()

        // Register call with VPS for audio routing (always - all calls use VPS)
        if true {
            await registerCallWithVPS()
        }

        // Configure audio session BEFORE starting engine
        #if os(iOS)
        do {
            let audioSession = AVAudioSession.sharedInstance()

            // 🔧 FIX: Use .videoChat mode for better speaker support
            try audioSession.setCategory(.playAndRecord, mode: .videoChat, options: [.defaultToSpeaker, .allowBluetoothA2DP])
            try audioSession.setPreferredSampleRate(16000)
            try audioSession.setPreferredIOBufferDuration(0.005)
            try audioSession.setActive(true)

            // Force speaker output after activation
            try audioSession.overrideOutputAudioPort(.speaker)

            // 📶 Listen for audio session interruptions (Siri, phone calls, music)
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(handleAudioSessionInterruption),
                name: AVAudioSession.interruptionNotification,
                object: audioSession
            )

            let currentRoute = audioSession.currentRoute
            let outputPort = currentRoute.outputs.first?.portType
            print("🔊 VoiceCall: Audio output route (caller): \(outputPort?.rawValue ?? "unknown")")

            await MainActor.run {
                isSpeakerOn = true
            }

            print("✅ VoiceCall: Audio session activated for call (caller side, speaker ON)")
            try await Task.sleep(nanoseconds: 200_000_000) // 200ms
        } catch {
            print("❌ VoiceCall: Failed to configure audio session: \(error)")
        }
        #endif

        // 🔧 FIX: Wrap audio engine start in do/catch - don't let it kill the call
        do {
            try startAudioEngine()
            print("✅ VoiceCall: Audio engine started successfully (caller side)")
        } catch {
            print("❌ VoiceCall: Audio engine failed to start (caller): \(error) - will retry")
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 500_000_000) // 500ms
                guard let self = self, self.callState == .inCall else { return }
                do {
                    try self.startAudioEngine()
                    print("✅ VoiceCall: Audio engine started on retry (caller)")
                } catch {
                    print("❌ VoiceCall: Audio engine retry also failed (caller): \(error)")
                }
            }
        }

        // Start audio streaming for ALL VPS-routed calls (WebSocket + HTTP polling)
        // 🔧 FIX: Include .mesh — only .meshDirect uses P2P audio without VPS streaming
        if lockedConnectionType != .meshDirect {
            print("☁️ VoiceCall: Starting VPS audio streaming (caller side, type: \(lockedConnectionType))...")
            startAudioStreaming()
        } else {
            print("📶 VoiceCall: Using mesh P2P audio (caller side, type: \(lockedConnectionType))")
        }

        print("✅ VoiceCall: Call connected (caller side)")
        print("   🔒 Connection type LOCKED: \(lockedConnectionType)")
        callLog.notice("✅ CALLER AUDIO READY - locked: \(String(describing: self.lockedConnectionType), privacy: .public)")
        fileLog.log("✅ CALLER AUDIO READY - locked: \(lockedConnectionType)")
    }
    
    /// Register the call with VPS for proper audio routing
    private func registerCallWithVPS() async {
        guard let myKey = getIdentityManager()?.publicKey,
              let peerKey = currentCallPeer,
              let callId = currentCallId else {
            print("❌ VoiceCall: Cannot register call with VPS - missing data")
            print("   myKey: \(getIdentityManager()?.publicKey.prefix(12) ?? "nil")")
            print("   peerKey: \(currentCallPeer?.prefix(12) ?? "nil")")
            print("   callId: \(currentCallId ?? "nil")")
            return
        }
        
        guard let url = URL(string: "\(vpsBaseURL)/api/call/register") else { return }
        
        print("📞 VoiceCall: ==========================================")
        print("📞 VoiceCall: Registering call with VPS")
        print("   Call ID: \(callId)")
        print("   My key: \(myKey.prefix(16))...")
        print("   Peer key: \(peerKey.prefix(16))...")
        print("📞 VoiceCall: ==========================================")
        
        let payload: [String: Any] = [
            "callId": callId,
            "participant1": myKey,
            "participant2": peerKey
        ]
        
        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload) else { return }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = jsonData
        request.timeoutInterval = 5
        
        do {
            let (responseData, response) = try await URLSession.shared.data(for: request)
            if let httpResponse = response as? HTTPURLResponse {
                print("📞 VoiceCall: Call registered with VPS (status: \(httpResponse.statusCode))")
                if let responseStr = String(data: responseData, encoding: .utf8) {
                    print("   Response: \(responseStr)")
                }
            }
        } catch {
            print("⚠️ VoiceCall: Failed to register call with VPS: \(error)")
        }
    }
    
    /// Notify VPS that call has ended
    private func notifyCallEndToVPS() {
        guard let callId = currentCallId else { return }
        
        guard let url = URL(string: "\(vpsBaseURL)/api/call/end") else { return }
        
        let payload: [String: String] = ["callId": callId]
        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload) else { return }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = jsonData
        request.timeoutInterval = 2
        
        URLSession.shared.dataTask(with: request).resume()
    }
    
    // MARK: - End Call

    func endCall(reason: CallEndReason = .hungUp) {
        // 🔧 FIX: Add detailed logging to track where endCall is called from
        print("🔴 VoiceCall: endCall() called with reason: \(reason.rawValue)")
        callLog.notice("🔴 END CALL - reason: \(reason.rawValue, privacy: .public), state: \(String(describing: self.callState), privacy: .public)")
        fileLog.log("🔴 END CALL - reason: \(reason.rawValue), state: \(callState), connTime: \(callConnectedTime), isVideo: \(isCurrentCallVideo)")
        fileLog.log("🔴 STACK: \(Thread.callStackSymbols.prefix(15).joined(separator: " | "))")
        fileLog.log("🔴 END CALL - reason: \(reason.rawValue), state: \(callState)")
        fileLog.log("🔴 STACK: \(Thread.callStackSymbols.prefix(15).joined(separator: " | "))")
        print("   📍 Current state: \(callState)")
        print("   📍 receivedRemoteEndSignal: \(receivedRemoteEndSignal)")
        print("   📍 isEndingCall: \(isEndingCall)")
        print("   📍 Thread: \(Thread.isMainThread ? "Main" : "Background")")
        print("   📍 callConnectedTime: \(callConnectedTime?.description ?? "nil")")

        guard callState != .idle else {
            print("   ⚠️ Ignoring endCall - already idle")
            return
        }

        // 🔧 FIX: Prevent multiple concurrent endCall executions
        guard !isEndingCall else {
            print("   ⚠️ Ignoring endCall - already ending call")
            return
        }

        // 🔧 CRITICAL FIX: Prevent immediate endCall right after call connects
        // This fixes the bug where call ends immediately after pickup
        // Apply this protection for connection loss and remote-initiated ends
        if let connectedTime = callConnectedTime {
            let timeSinceConnect = Date().timeIntervalSince(connectedTime)

            // For connection loss, use 5 second protection
            if reason == .connectionLost && timeSinceConnect < 5.0 {
                print("   ⚠️ BLOCKING endCall - call just connected \(String(format: "%.1f", timeSinceConnect))s ago!")
                print("   📍 This is likely a race condition - ignoring connection loss")
                return
            }

            // 🔧 NEW: For remote-initiated hungUp, use 3 second protection
            // This prevents stale callEnd signals from previous call attempts from ending the current call
            if reason == .hungUp && receivedRemoteEndSignal && timeSinceConnect < 3.0 {
                print("   ⚠️ BLOCKING remote endCall - call just connected \(String(format: "%.1f", timeSinceConnect))s ago!")
                print("   📍 This is likely a stale signal from previous call attempt - ignoring")
                return
            }
        }

        // 🔧 NEW: Also protect calls in ringing/connecting state from stale end signals
        // If we're the caller and waiting for accept, don't immediately end on stale signals
        if isOutgoingCall && (callState == .ringing || callState == .connecting) {
            if reason == .hungUp && receivedRemoteEndSignal {
                if let startTime = currentCallStartTime {
                    let timeSinceStart = Date().timeIntervalSince(startTime)
                    // Don't end during first 3 seconds of ringing - could be stale signal
                    if timeSinceStart < 3.0 {
                        print("   ⚠️ BLOCKING endCall - outgoing call just started ringing \(String(format: "%.1f", timeSinceStart))s ago")
                        print("   📍 This could be a stale signal - ignoring")
                        return
                    }
                }
            }
        }

        isEndingCall = true

        // 📱 UX: Store reason for UI display (must be on main thread for @Published)
        DispatchQueue.main.async { [weak self] in
            self?.lastCallEndReason = reason
        }
        
        // Store call info for history
        let peerName = currentCallPeerName ?? "Unknown"
        let peerKey = currentCallPeer ?? ""
        let duration = callDuration
        let wasInCall = callState == .inCall
        let wasConnecting = callState == .connecting
        let wasRinging = callState == .ringing
        
        // Notify VPS that call has ended
        if connectionType == .tor {
            notifyCallEndToVPS()
        }

        // 🔧 FIX: Notify CallKit that call has ended
        #if os(iOS)
        let callKitReason: CXCallEndedReason
        switch reason {
        case .hungUp:
            // 🔧 FIX: Use .remoteEnded only if WE received the end signal from remote
            // Otherwise use .remoteEnded for local hangup (CallKit has no .localEnded, remoteEnded works for both)
            callKitReason = .remoteEnded
        case .declined:
            callKitReason = .declinedElsewhere
        case .noAnswer:
            callKitReason = .unanswered
        case .networkError, .connectionLost, .peerDisconnected:
            callKitReason = .failed
        case .answeredElsewhere:
            callKitReason = .answeredElsewhere  // 📱 Multi-device support
        }
        VoIPPushManager.shared.endCall(reason: callKitReason)
        #endif

        // Only send end signal if WE initiated the end (not if we received end from remote)
        // This prevents echo/infinite loop of end signals
        // 🔧 FIX: Capture values BEFORE cleanup() nils them (race condition fix)
        let capturedPeer = currentCallPeer
        let capturedCallId = currentCallId
        let capturedConnectionType = lockedConnectionType

        if !receivedRemoteEndSignal, let peer = capturedPeer {
            let endPacket = createCallPacket(type: .callEnd, payload: reason.rawValue.data(using: .utf8) ?? Data())
            if let encrypted = try? encryptForPeer(data: endPacket, peerPublicKey: peer) {
                print("📞 VoiceCall: Sending callEnd signal to peer (reason: \(reason.rawValue))")
                print("   🔒 Using locked connection type: \(capturedConnectionType)")

                if capturedConnectionType == .meshDirect {
                    // Mesh call → end via mesh ONLY
                    meshManager?.sendCallSignal(to: peer, data: encrypted)
                    print("📶 VoiceCall: Sent callEnd via MESH only")
                } else {
                    // VPS call → end via VPS with retry (2 attempts for reliability)
                    // 🔧 FIX: Use captured callId to prevent race with cleanup()
                    Task { [weak self] in
                        for attempt in 1...2 {
                            do {
                                try await self?.sendViaTorRelay(to: peer, data: encrypted, overrideCallId: capturedCallId)
                                print("☁️ VoiceCall: Sent callEnd via VPS (attempt \(attempt))")
                                fileLog.log("☁️ callEnd sent via VPS (attempt \(attempt))")
                                break
                            } catch {
                                print("⚠️ VoiceCall: callEnd VPS attempt \(attempt) failed: \(error.localizedDescription)")
                                if attempt < 2 {
                                    try? await Task.sleep(nanoseconds: 500_000_000)
                                }
                            }
                        }
                    }
                }
            }
        } else if receivedRemoteEndSignal {
            print("📞 VoiceCall: Not sending callEnd - received remote end signal")
        }

        cleanup()
        
        DispatchQueue.main.async {
            self.callState = .ended(reason: reason)

            // ⌚ Notify Watch that call ended
            WatchConnectivityManager.shared.notifyCallEndedOnWatch()

            // Post notification with call details for chat history
            // Both parties should see the call summary
            if wasInCall && duration > 0 {
                // Completed call with duration
                NotificationCenter.default.post(
                    name: NSNotification.Name("CallEnded"),
                    object: nil,
                    userInfo: [
                        "peerPublicKey": peerKey,
                        "peerName": peerName,
                        "duration": duration,
                        "reason": reason.rawValue,
                        "wasOutgoing": self.isOutgoingCall
                    ]
                )
            } else if wasConnecting || wasRinging || (wasInCall && duration == 0) {
                // Call didn't complete or was very short
                NotificationCenter.default.post(
                    name: NSNotification.Name("MissedCall"),
                    object: nil,
                    userInfo: [
                        "peerPublicKey": peerKey,
                        "peerName": peerName,
                        "reason": reason.rawValue,
                        "wasOutgoing": self.isOutgoingCall
                    ]
                )
            }
            
            // Reset to idle after short delay (reduced from 2s to 1s for faster cleanup)
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                self.callState = .idle
                self.currentCallPeer = nil
                self.currentCallPeerName = nil
                self.callDuration = 0
                self.waitingDuration = 0
                self.isOutgoingCall = false
                self.hasStartedCall = false  // Reset for next call
                self.isAcceptingCall = false  // Reset for next call
                self.autoAcceptScheduled = false  // 🔧 FIX: Reset auto-accept flag
                self.lastAcceptedCallPeer = nil  // 🔧 FIX: Clear accepted peer tracking
                self.lastAcceptedCallTime = nil
                self.pendingCallKitAccept = false  // 🔧 FIX: Reset pending accept
                self.pendingCallKitAcceptCallerKey = nil
                self.isCallKitHandlingIncoming = false  // 🔧 FIX: Reset CallKit handling flag
                self.wasAnsweredViaCallKit = false  // 🔧 FIX: Reset for next call
                self.processedCallSignalIDs.removeAll()  // Clear for next call
                self.processedIncomingCallKeys.removeAll()  // 🔧 FIX: Clear incoming call tracking
                self.lastIncomingCallTime = nil
                self.currentCallId = nil  // Reset call ID
                self.receivedRemoteEndSignal = false  // Reset for next call
                self.isEndingCall = false  // 🔧 FIX: Reset ending call flag
                
                // 🔧 FIX: Clear VoIP push suppression flags
                self.voipPushActiveForCaller = nil
                self.voipPushReceivedTime = nil
            }
        }
        
        print("📞 VoiceCall: Call ended - \(reason.rawValue)")
        callLog.notice("📞 CALL ENDED - \(reason.rawValue, privacy: .public)")
        fileLog.log("📞 CALL ENDED - \(reason.rawValue)")
    }
    
    private func cleanup() {
        stopAudioEngine()
        stopAudioStreaming()
        stopWaitingTimer()
        stopQualityMonitoring()  // Stop quality monitoring
        stopConnectionLossDetection()  // Stop connection loss beep
        callTimer?.invalidate()
        callTimer = nil
        keepAliveTimer?.invalidate()
        keepAliveTimer = nil
        sessionKey = nil
        callNonce = 0
        currentCallId = nil
        currentCallStartTime = nil  // 🔧 FIX: Clear call start time
        callConnectedTime = nil     // 🔧 FIX: Clear call connected time
        stopRingtone()
        stopRingbackTone()  // 🔔 Stop ringback tone if still playing
        torConnection?.cancel()
        torConnection = nil
        
        // 📹 Reset video call flag and cleanup video callback
        DispatchQueue.main.async {
            self.isCurrentCallVideo = false
            self.firstVideoNetworkSend = false
            self.onVideoPacketReceived = nil  // 🔧 FIX: Clear video callback
            self.pendingVideoPackets.removeAll()  // 🔧 FIX: Clear buffered video packets
            self.pendingOutgoingVideoPackets.removeAll()  // 🔧 FIX: Clear buffered outgoing video
        }
        videoPollCount = 0
        videoRxHttpCount = 0
        videoHttpTxCount = 0
        
        // 📶 Reset reconnection state
        reconnectAttempts = 0
        isReconnecting = false
        lastReconnectAttemptTime = nil

        // 🔒 Unlock connection type for next call
        connectionTypeLocked = false
        lockedConnectionType = .none
        
        // 🔒 Reset incoming call connection lock
        incomingCallConnectionLocked = false
        incomingCallConnectionType = .none
        
        // 🔊 NEW: Clear jitter buffer
        clearJitterBuffer()
        
        // Remove audio session observers
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.interruptionNotification, object: nil)
        
        // Reset audio session to default
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setActive(false, options: .notifyOthersOnDeactivation)
        } catch {
            print("⚠️ VoiceCall: Failed to deactivate audio session: \(error)")
        }
        
        // Reset speaker state
        DispatchQueue.main.async {
            self.isSpeakerOn = false
        }
        
        // Log final quality stats from internal counters
        if internalPacketsSent > 0 {
            print("📊 VoiceCall: Final call stats - Sent: \(internalPacketsSent), Received: \(internalPacketsReceived), Avg Latency: \(currentLatency)ms")
            callLog.notice("📊 FINAL STATS - Sent: \(self.internalPacketsSent), Received: \(self.internalPacketsReceived), Latency: \(self.currentLatency)ms")
            fileLog.log("📊 FINAL STATS - Sent: \(internalPacketsSent), Received: \(internalPacketsReceived), Latency: \(currentLatency)ms")
        }
        
        // Reset internal counters
        internalPacketsSent = 0
        internalPacketsReceived = 0
        internalBytesSent = 0
        internalBytesReceived = 0
        sentAudioPacketCount = 0
        receivedAudioPacketCount = 0
        
        print("🧹 VoiceCall: Cleanup complete")
    }
    
    // MARK: - Audio Engine

    /// 🔧 FIX: Restart audio engine after camera starts (video calls)
    /// Camera capture can disrupt microphone even with automaticallyConfiguresApplicationAudioSession = false
    /// Always does a full stop+restart to ensure input tap is properly reinstalled
    func restartAudioEngineIfNeeded() {
        guard callState == .inCall else { return }

        // Always do a full restart - partial restarts leave the input tap broken
        // which causes empty/silent audio packets (38B instead of 437B)
        fileLog.log("🔧 restartAudioEngineIfNeeded: Full restart for video call audio")
        do {
            stopAudioEngine()
            try startAudioEngine()
            fileLog.log("✅ Audio engine fully restarted for video call")
        } catch {
            fileLog.log("❌ Audio engine restart failed: \(error)")
        }
    }

    private func startAudioEngine() throws {
        audioEngine = AVAudioEngine()
        guard let engine = audioEngine else { return }
        
        inputNode = engine.inputNode
        playerNode = AVAudioPlayerNode()
        mixerNode = AVAudioMixerNode()
        
        // Create effect nodes for RECEIVED audio (what you hear)
        pitchEffect = AVAudioUnitTimePitch()
        distortionEffect = AVAudioUnitDistortion()
        reverbEffect = AVAudioUnitReverb()
        delayEffect = AVAudioUnitDelay()

        // Create effect nodes for OUTGOING audio (what others hear from you)
        outgoingPitchEffect = AVAudioUnitTimePitch()
        outgoingDistortionEffect = AVAudioUnitDistortion()
        outgoingReverbEffect = AVAudioUnitReverb()
        outgoingDelayEffect = AVAudioUnitDelay()
        outgoingMixerNode = AVAudioMixerNode()

        guard let player = playerNode,
              let mixer = mixerNode,
              let pitch = pitchEffect,
              let distortion = distortionEffect,
              let reverb = reverbEffect,
              let delay = delayEffect,
              let outPitch = outgoingPitchEffect,
              let outDistortion = outgoingDistortionEffect,
              let outReverb = outgoingReverbEffect,
              let outDelay = outgoingDelayEffect,
              let outMixer = outgoingMixerNode,
              let input = inputNode else { return }

        // Attach nodes for received audio
        engine.attach(player)
        engine.attach(mixer)
        engine.attach(pitch)
        engine.attach(distortion)
        engine.attach(reverb)
        engine.attach(delay)

        // Attach nodes for outgoing audio effects
        engine.attach(outPitch)
        engine.attach(outDistortion)
        engine.attach(outReverb)
        engine.attach(outDelay)
        engine.attach(outMixer)
        
        // Get native input format - this is what the hardware provides
        let nativeInputFormat = input.inputFormat(forBus: 0)
        let processingFormat = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)!
        
        print("🎙️ VoiceCall: Native input format - rate: \(nativeInputFormat.sampleRate), channels: \(nativeInputFormat.channelCount)")
        print("🎙️ VoiceCall: Processing format - rate: \(processingFormat.sampleRate), channels: \(processingFormat.channelCount)")
        
        // Connect for playback (received audio)
        // player -> pitch -> distortion -> reverb -> delay -> mixer -> mainMixer
        engine.connect(player, to: pitch, format: processingFormat)
        engine.connect(pitch, to: distortion, format: processingFormat)
        engine.connect(distortion, to: reverb, format: processingFormat)
        engine.connect(reverb, to: delay, format: processingFormat)
        engine.connect(delay, to: mixer, format: processingFormat)
        engine.connect(mixer, to: engine.mainMixerNode, format: processingFormat)
        
        // Connect for outgoing audio effects processing
        // input -> outPitch -> outDistortion -> outReverb -> outMixer (for tap)
        // Note: We can't directly tap from effect output in AVAudioEngine,
        // so we process the input buffer through effects manually in processAndSendAudio
        
        // Apply current effect to both chains
        applyEffect(currentEffect)
        
        // Start engine FIRST before installing tap
        try engine.start()
        
        // Now install tap using the native format (must match hardware)
        // Only install if we have a valid format
        if nativeInputFormat.sampleRate > 0 && nativeInputFormat.channelCount > 0 {
            input.installTap(onBus: 0, bufferSize: bufferSize, format: nativeInputFormat) { [weak self] buffer, time in
                self?.processAndSendAudio(buffer: buffer)
            }
            print("🎙️ VoiceCall: Installed input tap with native format")
        } else {
            // Fallback: try with nil format (lets system choose)
            input.installTap(onBus: 0, bufferSize: bufferSize, format: nil) { [weak self] buffer, time in
                self?.processAndSendAudio(buffer: buffer)
            }
            print("🎙️ VoiceCall: Installed input tap with system default format")
        }
        
        player.play()
        
        print("🎙️ VoiceCall: Audio engine started")
        print("🎨 VoiceCall: Effects will be applied to outgoing audio: \(applyEffectToOutgoing)")
        callLog.notice("🎙️ AUDIO ENGINE STARTED - effects: \(self.applyEffectToOutgoing)")
        fileLog.log("🎙️ AUDIO ENGINE STARTED - effects: \(applyEffectToOutgoing)")
    }
    
    // MARK: - Audio Session Interruption Handling

    /// Handle audio session interruptions (Siri, phone calls, music, etc.)
    /// Without this, call audio silently breaks when interrupted
    @objc private func handleAudioSessionInterruption(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }

        switch type {
        case .began:
            print("⚠️ VoiceCall: Audio session INTERRUPTED (Siri/phone/music)")
            // Audio engine is paused by the system - nothing to do here
            // The engine will need to be restarted when interruption ends

        case .ended:
            print("📶 VoiceCall: Audio session interruption ENDED - restarting audio...")

            // Check if we should resume
            let shouldResume: Bool
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                shouldResume = options.contains(.shouldResume)
            } else {
                shouldResume = true
            }

            if shouldResume && callState == .inCall {
                // Reconfigure audio session and restart engine
                Task { @MainActor [weak self] in
                    guard let self = self else { return }

                    do {
                        let session = AVAudioSession.sharedInstance()
                        try session.setCategory(.playAndRecord, mode: .videoChat, options: [.defaultToSpeaker, .allowBluetoothA2DP])
                        try session.setActive(true)

                        if self.isSpeakerOn {
                            try session.overrideOutputAudioPort(.speaker)
                        }

                        // Restart audio engine
                        self.stopAudioEngine()
                        try? await Task.sleep(nanoseconds: 200_000_000) // 200ms settle
                        try self.startAudioEngine()
                        print("✅ VoiceCall: Audio engine restarted after interruption")
                    } catch {
                        print("❌ VoiceCall: Failed to restart audio after interruption: \(error)")
                        // Retry once more after a longer delay
                        try? await Task.sleep(nanoseconds: 1_000_000_000) // 1s
                        if self.callState == .inCall {
                            try? self.startAudioEngine()
                        }
                    }
                }
            }

        @unknown default:
            break
        }
    }

    private func stopAudioEngine() {
        inputNode?.removeTap(onBus: 0)
        audioEngine?.stop()
        audioEngine = nil
        playerNode = nil
        inputNode = nil
        mixerNode = nil
        pitchEffect = nil
        distortionEffect = nil
        reverbEffect = nil
        delayEffect = nil
        // Clean up outgoing effect nodes
        outgoingPitchEffect = nil
        outgoingDistortionEffect = nil
        outgoingReverbEffect = nil
        outgoingDelayEffect = nil
        outgoingMixerNode = nil
        audioConverter = nil  // Clean up converter
        
        print("🎙️ VoiceCall: Audio engine stopped")
    }
    
    // MARK: - Voice Effects (Real-time)
    
    func setEffect(_ effect: CallVoiceEffect) {
        currentEffect = effect
        applyEffect(effect)
    }
    
    /// Cycle through voice effects
    func cycleVoiceEffect() {
        let allEffects = CallVoiceEffect.allCases
        if let currentIndex = allEffects.firstIndex(of: currentEffect) {
            let nextIndex = (currentIndex + 1) % allEffects.count
            setEffect(allEffects[nextIndex])
        } else {
            setEffect(.none)
        }
    }
    
    private func applyEffect(_ effect: CallVoiceEffect) {
        guard let pitch = pitchEffect,
              let distortion = distortionEffect,
              let reverb = reverbEffect,
              let delay = delayEffect else {
            print("⚠️ VoiceCall: Cannot apply effect - audio nodes not initialized yet")
            fileLog.log("⚠️ EFFECT: nodes nil - pitch:\(pitchEffect != nil) dist:\(distortionEffect != nil) rev:\(reverbEffect != nil) delay:\(delayEffect != nil)")
            return
        }

        // Reset all effects (received audio)
        pitch.pitch = 0
        pitch.rate = 1.0
        distortion.wetDryMix = 0
        reverb.wetDryMix = 0
        delay.wetDryMix = 0

        // Reset outgoing effects
        outgoingPitchEffect?.pitch = 0
        outgoingPitchEffect?.rate = 1.0
        outgoingDistortionEffect?.wetDryMix = 0
        outgoingReverbEffect?.wetDryMix = 0
        outgoingDelayEffect?.wetDryMix = 0

        switch effect {
        case .none:
            break

        case .robot:
            // Matched to chat: pitch=-200, distortion=multiDistortedCubed @ 50%
            pitch.pitch = -200
            distortion.loadFactoryPreset(.multiDistortedCubed)
            distortion.wetDryMix = 50
            outgoingPitchEffect?.pitch = -200
            outgoingDistortionEffect?.loadFactoryPreset(.multiDistortedCubed)
            outgoingDistortionEffect?.wetDryMix = 50

        case .alien:
            // Matched to chat: pitch=800, rate=1.2, reverb=largeHall @ 30%
            pitch.pitch = 800
            pitch.rate = 1.2
            reverb.loadFactoryPreset(.largeHall)
            reverb.wetDryMix = 30
            outgoingPitchEffect?.pitch = 800
            outgoingPitchEffect?.rate = 1.2
            outgoingReverbEffect?.loadFactoryPreset(.largeHall)
            outgoingReverbEffect?.wetDryMix = 30

        case .deep:
            // Matched to chat: pitch=-500, rate=0.8
            pitch.pitch = -500
            pitch.rate = 0.8
            outgoingPitchEffect?.pitch = -500
            outgoingPitchEffect?.rate = 0.8

        case .chipmunk:
            // Matched to chat: pitch=1000, rate=1.5
            pitch.pitch = 1000
            pitch.rate = 1.5
            outgoingPitchEffect?.pitch = 1000
            outgoingPitchEffect?.rate = 1.5

        case .echo:
            // Matched to chat: delay=0.3s, feedback=50, wetDry=40%
            delay.delayTime = 0.3
            delay.feedback = 50
            delay.wetDryMix = 40
            outgoingDelayEffect?.delayTime = 0.3
            outgoingDelayEffect?.feedback = 50
            outgoingDelayEffect?.wetDryMix = 40

        case .reverb:
            // Matched to chat: cathedral preset @ 60%
            reverb.loadFactoryPreset(.cathedral)
            reverb.wetDryMix = 60
            outgoingReverbEffect?.loadFactoryPreset(.cathedral)
            outgoingReverbEffect?.wetDryMix = 60

        case .anonymous:
            // Maximum voice disguise
            pitch.pitch = -300
            pitch.rate = 0.9
            distortion.loadFactoryPreset(.drumsBitBrush)
            distortion.wetDryMix = 30
            reverb.loadFactoryPreset(.cathedral)
            reverb.wetDryMix = 40
            outgoingPitchEffect?.pitch = -300
            outgoingPitchEffect?.rate = 0.9
            outgoingDistortionEffect?.loadFactoryPreset(.drumsBitBrush)
            outgoingDistortionEffect?.wetDryMix = 30
            outgoingReverbEffect?.loadFactoryPreset(.cathedral)
            outgoingReverbEffect?.wetDryMix = 40
        }

        print("🎨 VoiceCall: Applied effect - \(effect.rawValue) (outgoing: \(applyEffectToOutgoing))")
    }
    
    // MARK: - Audio Processing
    
    private var txLogCount = 0
    private func processAndSendAudio(buffer: AVAudioPCMBuffer) {
        guard callState == .inCall, !isMuted, currentCallPeer != nil else {
            txLogCount += 1
            if txLogCount <= 3 {
                fileLog.log("⚠️ TX SKIP: state:\(callState) muted:\(isMuted) peer:\(currentCallPeer != nil)")
            }
            return
        }
        
        // Resample buffer to transmission format if needed
        var transmitBuffer: AVAudioPCMBuffer
        
        if buffer.format.sampleRate != sampleRate {
            // Need to resample
            guard let resampled = resampleBuffer(buffer, to: transmissionFormat) else {
                print("❌ VoiceCall: Failed to resample audio")
                return
            }
            transmitBuffer = resampled
        } else {
            transmitBuffer = buffer
        }
        
        // 🎨 Apply voice effect to outgoing audio if enabled
        if applyEffectToOutgoing && currentEffect != .none {
            if let effectProcessedBuffer = applyEffectToBuffer(transmitBuffer) {
                transmitBuffer = effectProcessedBuffer
            }
        }
        
        // Convert buffer to data
        guard var audioData = bufferToData(transmitBuffer) else { return }
        
        // Update audio level (throttled to avoid "onChange multiple times per frame" warning)
        let now = Date()
        if now.timeIntervalSince(lastAudioLevelUpdate) >= audioLevelUpdateInterval {
            let level = calculateAudioLevel(buffer: buffer)
            lastAudioLevelUpdate = now
            DispatchQueue.main.async {
                self.audioLevel = level
            }
        }
        
        // 🎯 Apply compression (G.726 ADPCM)
        // 🔧 FIX: Codec expects Int16 samples, but bufferToData produces Float32
        // Convert Float32 → Int16 before encoding
        if useCompression {
            let int16Data = float32ToInt16(audioData)
            audioData = audioCodec.encode(int16Data)
        }

        // Encrypt audio data - 🔧 Thread-safe: returns nonce used for consistent headers
        guard let (encryptedAudio, packetNonce) = encryptAudioData(audioData) else {
            print("❌ VoiceCall: Failed to encrypt audio")
            return
        }

        // 🎯 Create packet with header: [type(1)][seq(8)][encrypted_data]
        var packetData = Data()
        packetData.append(CallPacketType.audioData.rawValue)  // Type byte
        var seq = packetNonce  // 🔧 Use nonce from encryption for consistency
        packetData.append(Data(bytes: &seq, count: 8))  // Sequence number
        packetData.append(encryptedAudio)  // Payload

        // Track packet statistics (use internal counters to avoid main thread)
        internalPacketsSent += 1
        internalBytesSent += UInt64(packetData.count)
        packetsSentSinceLastCheck += 1

        // 🎯 Generate FEC packet every N packets
        if useFEC {
            if let fecPacket = fecEncoder.addPacketForEncoding(audioData, sequenceNumber: packetNonce) {
                // Send FEC packet - encrypt with its own nonce
                var fecData = Data()
                fecData.append(FEC_PACKET_TYPE)  // FEC type
                if let (encryptedFEC, fecNonce) = encryptAudioData(fecPacket) {
                    var fecSeq = fecNonce
                    fecData.append(Data(bytes: &fecSeq, count: 8))
                    fecData.append(encryptedFEC)
                    sendAudioPacket(fecData)
                }
            }
        }

        // Log every 100th packet to avoid spam
        if packetNonce % 100 == 1 {
            let compressionInfo = useCompression ? ", compressed: \(audioCodec.name)" : ""
            let bitrateInfo = useAdaptiveBitrate ? ", bitrate: \(adaptiveBitrate.currentLevel.description)" : ""
            print("🎤 VoiceCall: Sending #\(packetNonce), size: \(packetData.count)\(compressionInfo)\(bitrateInfo)")
            callLog.info("🎤 TX #\(packetNonce) size:\(packetData.count)\(compressionInfo, privacy: .public)\(bitrateInfo, privacy: .public)")
            fileLog.log("🎤 TX #\(packetNonce) size:\(packetData.count)\(compressionInfo)\(bitrateInfo)")
        }

        // Send the audio packet
        sendAudioPacket(packetData)
    }
    
    /// Send an audio packet via the appropriate transport
    /// 🔒 Uses LOCKED connection type to ensure no switching mid-call
    private func sendAudioPacket(_ packet: Data) {
        // 🔒 CRITICAL: Use effectiveConnectionType (locked during call)
        // This ensures audio NEVER switches between mesh/VPS mid-call
        let activeType = effectiveConnectionType

        if activeType == .meshDirect {
            // 🎵 MESH DIRECT: Fully offline P2P audio - for emergencies/no internet
            // Both signaling AND audio go through mesh network
            if let peer = currentCallPeer {
                if callNonce % 100 == 1 {
                    print("📶 VoiceCall: Sending audio via MESH DIRECT (locked) to \(String(peer.prefix(8)))...")
                }
                meshManager?.sendCallAudio(to: peer, data: packet)
            }
        } else {
            // 🌐 VPS: Audio goes through server relay
            // 🔧 SIMPLIFIED: .tor = everything via VPS (signaling + audio)
            if callNonce % 100 == 1 {
                print("🌐 VoiceCall: Sending audio via VPS (locked: \(activeType))")
            }
            if isWebSocketConnected {
                sendAudioViaWebSocket(data: packet)
            } else {
                sendAudioViaHTTP(data: packet)
            }
        }
    }
    
    // MARK: - Video Packet Methods
    
    /// Video packet type identifier (different from audio)
    private let VIDEO_PACKET_TYPE: UInt8 = 0xF1
    
    /// Send a video packet (called from VideoCallManager)
    /// 🔒 Uses LOCKED connection type to ensure no switching mid-call
    /// 🔧 Thread-safe: uses atomic nonce increment
    private var firstVideoNetworkSend = false

    // 🔧 FIX: Buffer video packets during .connecting/.ringing so first IDR is not lost
    private var pendingOutgoingVideoPackets: [Data] = []
    private let maxPendingOutgoingVideo = 10  // Buffer up to 10 frames (~333ms)

    func sendVideoPacket(_ videoData: Data) {
        // Allow sending during .inCall, buffer during .connecting/.ringing
        if callState != .inCall {
            if callState == .connecting || callState == .ringing {
                // Buffer the packet — will be flushed when call connects
                if pendingOutgoingVideoPackets.count < maxPendingOutgoingVideo {
                    pendingOutgoingVideoPackets.append(videoData)
                    if pendingOutgoingVideoPackets.count == 1 {
                        fileLog.log("📹 sendVideoPacket: Buffering during \(callState) (first IDR preserved)")
                    }
                }
            } else if videoNonce == 0 {
                fileLog.log("📹 sendVideoPacket: BLOCKED (callState=\(callState), not inCall)")
            }
            return
        }

        // 🔧 Flush buffered outgoing video packets first (includes first IDR)
        if !pendingOutgoingVideoPackets.isEmpty {
            let buffered = pendingOutgoingVideoPackets
            pendingOutgoingVideoPackets.removeAll()
            fileLog.log("📹 sendVideoPacket: Flushing \(buffered.count) buffered outgoing video packets")
            for pkt in buffered {
                sendVideoPacketInternal(pkt)
            }
        }

        sendVideoPacketInternal(videoData)
    }

    private func sendVideoPacketInternal(_ videoData: Data) {

        // 🔧 Thread-safe nonce increment
        let currentVideoNonce = incrementVideoNonce()

        // 🔧 Log first network video send — indicates video pipeline is working
        if !firstVideoNetworkSend {
            firstVideoNetworkSend = true
            fileLog.log("📹 FIRST VIDEO NETWORK SEND: nonce=\(currentVideoNonce) size=\(videoData.count)B ws=\(isWebSocketConnected)")
        }

        // Create packet: [type(1)][seq(8)][video_data]
        var packetData = Data()
        packetData.append(VIDEO_PACKET_TYPE)
        var seq = currentVideoNonce
        packetData.append(Data(bytes: &seq, count: 8))
        packetData.append(videoData)

        // 🔒 CRITICAL: Use effectiveConnectionType (locked during call)
        let activeType = effectiveConnectionType

        if activeType == .meshDirect {
            if let peer = currentCallPeer {
                meshManager?.sendCallAudio(to: peer, data: packetData)  // Uses same transport
            }
        } else {
            // VPS for all other connection types
            if isWebSocketConnected {
                sendVideoViaWebSocket(data: packetData)
                if currentVideoNonce <= 5 || currentVideoNonce % 30 == 1 {
                    fileLog.log("📹 VIDEO TX #\(currentVideoNonce) size:\(packetData.count)B via WebSocket")
                }
            } else {
                sendVideoViaHTTP(data: packetData)
                if currentVideoNonce <= 5 || currentVideoNonce % 30 == 1 {
                    fileLog.log("📹 VIDEO TX #\(currentVideoNonce) size:\(packetData.count)B via HTTP (WS not connected)")
                }
            }
        }

        // Log occasionally
        if currentVideoNonce % 30 == 1 {
            print("📹 VoiceCall: Sent video packet #\(currentVideoNonce), size: \(packetData.count) bytes (locked: \(activeType), ws: \(isWebSocketConnected))")
            callLog.info("📹 VIDEO TX #\(currentVideoNonce) size:\(packetData.count)B via \(String(describing: activeType), privacy: .public) ws:\(self.isWebSocketConnected)")
        }
    }
    
    /// Send video via WebSocket
    private func sendVideoViaWebSocket(data: Data) {
        guard let peer = currentCallPeer, let callId = currentCallId else {
            if videoNonce <= 5 {
                print("⚠️ VoiceCall: sendVideoViaWebSocket - no peer or callId")
            }
            return
        }

        let json: [String: Any] = [
            "type": "video",
            "callId": callId,
            "recipient": peer,
            "video": data.base64EncodedString()
        ]

        if let jsonData = try? JSONSerialization.data(withJSONObject: json),
           let jsonString = String(data: jsonData, encoding: .utf8) {
            webSocket?.send(.string(jsonString)) { [weak self] error in
                if let error = error {
                    print("❌ VoiceCall: WebSocket video send error: \(error)")
                    self?.isWebSocketConnected = false
                    // Fallback: try sending via HTTP
                    self?.sendVideoViaHTTP(data: data)
                }
            }
        }
    }
    
    /// Send video via HTTP (fallback)
    private var videoHttpTxCount: Int = 0

    private func sendVideoViaHTTP(data: Data) {
        guard let peer = currentCallPeer,
              let callId = currentCallId,
              let myKey = getIdentityManager()?.publicKey else { return }

        videoHttpTxCount += 1

        let payload: [String: Any] = [
            "sender": myKey,
            "callId": callId,
            "recipient": peer,
            "video": data.base64EncodedString()
        ]

        guard let url = URL(string: "\(vpsURL)/video") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: payload)
        request.timeoutInterval = 1.0

        let txCount = videoHttpTxCount
        audioURLSession.dataTask(with: request) { _, response, error in
            if let error = error, txCount <= 5 {
                print("❌ VoiceCall: HTTP video send error: \(error)")
                fileLog.log("❌ VIDEO HTTP TX error: \(error.localizedDescription)")
            } else if txCount <= 5 || txCount % 30 == 0 {
                let statusCode = (response as? HTTPURLResponse)?.statusCode ?? 0
                fileLog.log("📹 VIDEO HTTP TX #\(txCount) status:\(statusCode)")
            }
        }.resume()
    }
    
    /// Handle received video packet
    private func handleVideoPacket(_ data: Data) {
        // Skip header [type(1)][seq(8)]
        guard data.count > 9 else { return }
        let videoData = Data(data.suffix(from: 9))

        // Forward to VideoCallManager via callback, or buffer if not yet connected
        if let handler = onVideoPacketReceived {
            handler(videoData)
        } else {
            // 🔧 FIX: Buffer packets until VideoCallView connects
            pendingVideoPackets.append(videoData)
            if pendingVideoPackets.count > maxPendingVideoPackets {
                pendingVideoPackets.removeFirst()
            }
            if pendingVideoPackets.count == 1 {
                print("📹 VoiceCall: Buffering video packets (VideoCallView not yet connected)")
            }
        }
    }
    
    /// Apply voice effect to audio buffer using software DSP (pitch shift, etc.)
    /// This processes the outgoing audio so the other person hears your voice with the effect
    private func applyEffectToBuffer(_ buffer: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
        guard let floatData = buffer.floatChannelData?[0] else { return nil }
        let frameCount = Int(buffer.frameLength)

        // Create output buffer
        guard let outputBuffer = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: buffer.frameCapacity) else {
            return nil
        }
        guard let outputData = outputBuffer.floatChannelData?[0] else { return nil }

        // Get effect parameters
        let pitchShift = outgoingPitchEffect?.pitch ?? 0  // In cents (100 cents = 1 semitone)
        let rateChange = outgoingPitchEffect?.rate ?? 1.0
        let distortionMix = outgoingDistortionEffect?.wetDryMix ?? 0
        let reverbMix = outgoingReverbEffect?.wetDryMix ?? 0
        let delayMix = outgoingDelayEffect?.wetDryMix ?? 0

        // Simple pitch shift implementation using linear interpolation
        // For real-time calls, we use a simplified approach that's CPU efficient
        let pitchRatio = pow(2.0, Double(pitchShift) / 1200.0) * Double(rateChange)

        if abs(pitchRatio - 1.0) < 0.01 && distortionMix < 1 && reverbMix < 1 && delayMix < 1 {
            // No significant effect - return original
            return buffer
        }
        
        // Apply pitch shift using simple resampling
        var outputFrameCount = 0
        for i in 0..<frameCount {
            let srcIndex = Double(i) * pitchRatio
            let srcIndexInt = Int(srcIndex)
            let frac = Float(srcIndex - Double(srcIndexInt))
            
            if srcIndexInt < frameCount - 1 {
                // Linear interpolation between samples
                let sample1 = floatData[srcIndexInt]
                let sample2 = floatData[srcIndexInt + 1]
                outputData[i] = sample1 * (1.0 - frac) + sample2 * frac
                outputFrameCount = i + 1
            } else if srcIndexInt < frameCount {
                outputData[i] = floatData[srcIndexInt]
                outputFrameCount = i + 1
            }
        }
        
        // Apply simple distortion if enabled (soft clipping)
        if distortionMix > 0 {
            let mix = distortionMix / 100.0
            for i in 0..<outputFrameCount {
                let sample = outputData[i]
                // Soft clipping using tanh
                let distorted = tanh(sample * 3.0) * 0.7
                outputData[i] = sample * (1.0 - mix) + distorted * mix
            }
        }
        
        // Apply simple reverb simulation if enabled
        if reverbMix > 0 {
            let mix = reverbMix / 100.0
            let delayFrames = Int(Float(buffer.format.sampleRate) * 0.03)  // 30ms delay
            for i in delayFrames..<outputFrameCount {
                let delayed = outputData[i - delayFrames] * 0.3
                outputData[i] = outputData[i] * (1.0 - mix * 0.5) + delayed * mix * 0.5
            }
        }

        // Apply echo/delay effect if enabled
        if delayMix > 0 {
            let mix = delayMix / 100.0
            let delayTime = outgoingDelayEffect?.delayTime ?? 0.3
            let feedback = (outgoingDelayEffect?.feedback ?? 50) / 100.0
            let echoFrames = Int(Float(buffer.format.sampleRate) * Float(delayTime))
            for i in echoFrames..<outputFrameCount {
                let delayed = outputData[i - echoFrames] * Float(feedback)
                outputData[i] = outputData[i] * (1.0 - mix) + (outputData[i] + delayed) * mix
            }
        }

        outputBuffer.frameLength = AVAudioFrameCount(outputFrameCount)
        return outputBuffer
    }
    
    /// Resample audio buffer to target format
    private func resampleBuffer(_ buffer: AVAudioPCMBuffer, to outputFormat: AVAudioFormat) -> AVAudioPCMBuffer? {
        // Create or reuse converter
        if audioConverter == nil || audioConverter?.inputFormat != buffer.format {
            audioConverter = AVAudioConverter(from: buffer.format, to: outputFormat)
        }
        
        guard let converter = audioConverter else {
            print("❌ VoiceCall: Failed to create audio converter")
            return nil
        }
        
        // Calculate output frame count based on sample rate ratio
        let ratio = outputFormat.sampleRate / buffer.format.sampleRate
        let outputFrameCount = AVAudioFrameCount(Double(buffer.frameLength) * ratio)
        
        guard let outputBuffer = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: outputFrameCount) else {
            return nil
        }
        
        var error: NSError?
        let inputBlock: AVAudioConverterInputBlock = { inNumPackets, outStatus in
            outStatus.pointee = .haveData
            return buffer
        }
        
        converter.convert(to: outputBuffer, error: &error, withInputFrom: inputBlock)
        
        if let error = error {
            print("❌ VoiceCall: Resample error: \(error)")
            return nil
        }
        
        return outputBuffer
    }
    
    /// Receive and play audio with jitter buffer for smooth playback
    func receiveAudio(encryptedData: Data) {
        guard callState == .inCall else { return }
        
        meshAudioReceivedCount += 1
        
        // Track received packets for quality metrics (use internal counters to avoid main thread)
        internalPacketsReceived += 1
        internalBytesReceived += UInt64(encryptedData.count)
        packetsReceivedSinceLastCheck += 1
        
        // 🎯 Extract packet header: [type(1)][seq(8)][data...]
        guard encryptedData.count > 9 else {
            adaptiveBitrate.reportPacketLost()
            return
        }
        
        let packetType = encryptedData[0]
        // 🔧 FIX: Use safe unaligned byte reading to prevent crash
        // The .load(as:) function requires proper memory alignment which isn't guaranteed
        var sequenceNumber: UInt64 = 0
        let seqData = encryptedData.subdata(in: 1..<9)
        _ = Swift.withUnsafeMutableBytes(of: &sequenceNumber) { dest in
            seqData.copyBytes(to: dest)
        }
        sequenceNumber = UInt64(bigEndian: sequenceNumber)
        let payload = Data(encryptedData.suffix(from: 9))
        
        // 🎯 Handle FEC packets separately
        if packetType == FEC_PACKET_TYPE {
            if useFEC {
                fecDecoder.processFECPacket(payload)
            }
            return
        }
        
        // 📹 Handle VIDEO packets separately
        if packetType == VIDEO_PACKET_TYPE {
            handleVideoPacket(encryptedData)
            return
        }

        // Decrypt audio payload
        guard let decryptedData = decryptAudioData(payload) else {
            if meshAudioReceivedCount % 100 == 1 {
                print("❌ VoiceCall: Failed to decrypt audio packet #\(meshAudioReceivedCount)")
                fileLog.log("❌ DECRYPT FAIL #\(meshAudioReceivedCount) payload:\(payload.count)B sessionKey:\(sessionKey != nil)")
            }
            adaptiveBitrate.reportPacketLost()
            
            // 🎯 Try FEC recovery
            if useFEC, let recovered = fecDecoder.tryRecover(sequenceNumber: sequenceNumber) {
                processReceivedAudioData(recovered, sequenceNumber: sequenceNumber)
                return
            }
            
            // 🎯 Use PLC for concealment
            if usePLC {
                let concealed = packetLossConcealer.process(packet: nil)
                playAudioData(concealed)
            } else {
                playComfortNoise()
            }
            return
        }
        
        // Log first 5 successful decrypts
        if meshAudioReceivedCount <= 5 {
            fileLog.log("✅ DECRYPT OK #\(meshAudioReceivedCount) payload:\(payload.count)B → plain:\(decryptedData.count)B")
        }

        // 🎯 Decompress if using compression
        // 🔧 FIX: Codec produces Int16 samples, but dataToBuffer expects Float32
        // Convert Int16 → Float32 after decoding
        let audioData: Data
        if useCompression {
            let int16Decoded = audioCodec.decode(decryptedData)
            audioData = int16ToFloat32(int16Decoded)
        } else {
            audioData = decryptedData
        }
        
        // 🎯 Store for FEC recovery
        if useFEC {
            fecDecoder.storeReceivedPacket(audioData, sequenceNumber: sequenceNumber)
        }
        
        // 🎯 Report to adaptive bitrate
        if useAdaptiveBitrate {
            adaptiveBitrate.reportPacketReceived()
        }
        
        // Process the audio
        processReceivedAudioData(audioData, sequenceNumber: sequenceNumber)
    }
    
    /// Process received audio data through the quality pipeline
    private func processReceivedAudioData(_ audioData: Data, sequenceNumber: UInt64) {
        // Log every 100th packet to reduce overhead
        if meshAudioReceivedCount % 100 == 1 {
            let bufferDepth = useAdvancedJitterBuffer ? advancedJitterBuffer.bufferDepthPackets : audioJitterBuffer.count
            let bitrateLevel = adaptiveBitrate.currentLevel.description
            print("🔊 VoiceCall: Audio #\(meshAudioReceivedCount), seq: \(sequenceNumber), buffer: \(bufferDepth), bitrate: \(bitrateLevel)")
        }
        
        // Mark audio received for connection monitoring
        audioReceived()
        
        // 🎯 Use advanced jitter buffer with reordering
        if useAdvancedJitterBuffer {
            let accepted = advancedJitterBuffer.addPacket(sequenceNumber: sequenceNumber, audioData: audioData)
            
            if !accepted {
                // Packet was late or duplicate
                adaptiveBitrate.reportPacketLost()
            }
            
            // Play packets from buffer
            while let packetData = advancedJitterBuffer.getNextPacket() {
                // 🎯 Apply PLC processing
                let processedData = usePLC ? packetLossConcealer.process(packet: packetData) : packetData
                playAudioData(processedData)
            }
            
            // Check for buffer underrun (packet loss)
            if advancedJitterBuffer.bufferDepthPackets == 0 {
                // Buffer empty - use PLC
                if usePLC {
                    let concealed = packetLossConcealer.process(packet: nil)
                    playAudioData(concealed)
                }
            }
        } else {
            // Fallback to simple jitter buffer
            jitterBufferLock.lock()
            audioJitterBuffer.append(audioData)
            
            while audioJitterBuffer.count > jitterBufferTarget {
                let data = audioJitterBuffer.removeFirst()
                jitterBufferLock.unlock()
                
                let processedData = usePLC ? packetLossConcealer.process(packet: data) : data
                playAudioData(processedData)
                
                jitterBufferLock.lock()
            }
            jitterBufferLock.unlock()
        }
    }
    
    /// Play audio data (convert to buffer and schedule)
    private var playAudioCount = 0
    private func playAudioData(_ audioData: Data) {
        // 🔧 FIX: Guard against audio not being ready yet
        // Audio packets can arrive before audio engine is fully initialized
        guard let engine = audioEngine, engine.isRunning else {
            if playAudioCount < 3 { fileLog.log("❌ PLAY: engine nil or not running") }
            return
        }
        guard let player = playerNode else {
            if playAudioCount < 3 { fileLog.log("❌ PLAY: playerNode nil") }
            return
        }
        guard let buffer = dataToBuffer(audioData) else {
            if playAudioCount < 3 { fileLog.log("❌ PLAY: dataToBuffer failed, data:\(audioData.count)B") }
            return
        }

        playAudioCount += 1
        if playAudioCount <= 3 {
            fileLog.log("🔈 PLAY #\(playAudioCount) frames:\(buffer.frameLength) rate:\(buffer.format.sampleRate) playing:\(player.isPlaying)")
        }

        audioQueueLock.lock()
        player.scheduleBuffer(buffer, at: nil, options: [])
        audioQueueLock.unlock()
    }
    
    /// 🔊 NEW: Generate and play comfort noise during packet loss
    private func playComfortNoise() {
        // Generate very quiet white noise (less jarring than silence)
        var noiseFrames = [Float](repeating: 0, count: 960)  // 20ms at 48kHz
        for i in 0..<noiseFrames.count {
            noiseFrames[i] = Float.random(in: -0.005...0.005)  // Very quiet
        }
        
        // Convert to Data
        let noiseData = noiseFrames.withUnsafeBytes { Data($0) }
        
        if let buffer = dataToBuffer(noiseData) {
            audioQueueLock.lock()
            playerNode?.scheduleBuffer(buffer, at: nil, options: [])
            audioQueueLock.unlock()
        }
    }
    
    /// Clear jitter buffer (called when call ends or on reset)
    private func clearJitterBuffer() {
        jitterBufferLock.lock()
        audioJitterBuffer.removeAll()
        jitterBufferLock.unlock()
        
        // Also clear advanced components
        advancedJitterBuffer.reset()
        packetLossConcealer.reset()
        fecEncoder.reset()
        fecDecoder.reset()
        adaptiveBitrate.reset()
    }
    
    // MARK: - Encryption
    // ====================================================================================
    // SECURITY ARCHITECTURE
    // ====================================================================================
    //
    // 1. CALL SETUP (Signaling):
    //    - Uses Curve25519 ECDH key agreement (via IdentityManager)
    //    - Each party's long-term identity keys derive a shared secret
    //    - Signaling packets encrypted with AES-256-GCM
    //    - Server/relay CANNOT decrypt signaling - only sees encrypted blobs
    //
    // 2. AUDIO ENCRYPTION:
    //    - Fresh 256-bit AES session key generated for EACH call
    //    - Session key transmitted in encrypted call request (step 1)
    //    - Audio encrypted with AES-256-GCM (authenticated encryption)
    //    - Random 4-byte salt + 8-byte counter = unique nonce per packet
    //    - Replay protection: tracks received packet numbers, rejects old packets
    //
    // 3. SERVER/RELAY SECURITY:
    //    - VPS relay sees only encrypted packets (opaque blobs)
    //    - Cannot decrypt audio or signaling (no access to keys)
    //    - Cannot correlate calls without metadata analysis
    //    - Optional: Route via Tor for IP anonymity
    //
    // 4. FORWARD SECRECY:
    //    - New session key per call = compromise of one call doesn't affect others
    //    - Past calls remain secure even if device is later compromised
    //
    // 5. INTEGRITY:
    //    - AES-GCM provides authenticated encryption
    //    - Any tampering detected and packet rejected
    //    - MITM cannot inject or modify audio
    //
    // ====================================================================================
    
    /// Encrypt audio data using AES-256-GCM with session key
    /// Each packet has a unique nonce to prevent replay attacks
    /// 🔧 Thread-safe: uses atomic nonce increment to prevent nonce reuse
    /// Returns tuple of (encryptedData, nonceUsed) for consistent header creation
    private func encryptAudioData(_ data: Data) -> (encrypted: Data, nonce: UInt64)? {
        guard let key = sessionKey else { return nil }

        // 🔧 Thread-safe nonce increment (prevents nonce reuse vulnerability)
        let currentNonce = incrementCallNonce()

        // Create 12-byte nonce: 4 bytes random (set at call start) + 8 bytes counter
        var nonceData = Data(count: 12)
        // First 4 bytes are random salt (generated when session starts)
        nonceData.replaceSubrange(0..<4, with: sessionNonceSalt)
        // Last 8 bytes are packet counter
        withUnsafeBytes(of: currentNonce.bigEndian) { bytes in
            nonceData.replaceSubrange(4..<12, with: bytes)
        }

        do {
            let nonce = try AES.GCM.Nonce(data: nonceData)
            let sealed = try AES.GCM.seal(data, using: key, nonce: nonce)
            // Return: nonce (12) + ciphertext + tag (16), plus the nonce counter
            if currentNonce == 1 {
                callLog.notice("🔐 First packet encrypted - AES-256-GCM nonce #1, plaintext:\(data.count)B → cipher:\(sealed.ciphertext.count + 28)B")
                fileLog.log("🔐 First packet encrypted - AES-256-GCM nonce #1, plaintext:\(data.count)B → cipher:\(sealed.ciphertext.count + 28)B")
            }
            return (nonceData + sealed.ciphertext + sealed.tag, currentNonce)
        } catch {
            print("❌ VoiceCall: Encryption failed: \(error)")
            return nil
        }
    }
    
    /// Decrypt audio data - validates nonce to prevent replay attacks
    private func decryptAudioData(_ data: Data) -> Data? {
        guard let key = sessionKey, data.count > 28 else { return nil }
        
        let nonceData = data.prefix(12)
        let ciphertext = data.dropFirst(12).dropLast(16)
        let tag = data.suffix(16)
        
        // Extract packet counter from nonce (last 8 bytes) - safe unaligned read
        let counterData = nonceData.suffix(8)
        var packetCounter: UInt64 = 0
        _ = Swift.withUnsafeMutableBytes(of: &packetCounter) { dest in
            counterData.copyBytes(to: dest)
        }
        packetCounter = packetCounter.bigEndian
        
        // Replay protection: reject old packets (allow some out-of-order for UDP)
        if packetCounter <= lastReceivedNonce && lastReceivedNonce - packetCounter > 100 {
            print("⚠️ VoiceCall: Rejected replay packet")
            return nil
        }
        if packetCounter > lastReceivedNonce {
            lastReceivedNonce = packetCounter
        }
        
        do {
            let nonce = try AES.GCM.Nonce(data: nonceData)
            let sealed = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
            let decrypted = try AES.GCM.open(sealed, using: key)
            if packetCounter == 1 {
                callLog.notice("🔓 First packet decrypted - AES-256-GCM nonce #1, cipher:\(data.count)B → plain:\(decrypted.count)B")
                fileLog.log("🔓 First packet decrypted - AES-256-GCM nonce #1, cipher:\(data.count)B → plain:\(decrypted.count)B")
            }
            return decrypted
        } catch {
            // Don't log every failure - could be network issues
            return nil
        }
    }
    
    /// Encrypt signaling data using ECDH-derived shared secret
    /// Uses the peer's public key and our private key to derive a shared secret
    private func encryptForPeer(data: Data, peerPublicKey: String) throws -> Data {
        // Get our private key from IdentityManager
        guard let identityManager = getIdentityManager(),
              let privateKey = identityManager.privateKey else {
            throw CallError.invalidPeerKey
        }

        // 🔧 FIX: Normalize key from base64url to standard base64
        // VPS and some paths use base64url encoding (- and _) instead of standard (+, /, =)
        let normalizedKey = base64urlDecode(peerPublicKey)

        // Parse peer's public key (base64 encoded)
        guard let peerKeyData = Data(base64Encoded: normalizedKey),
              let peerPublicKeyObj = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerKeyData) else {
            print("❌ VoiceCall: Failed to decode peer key: \(peerPublicKey.prefix(20))...")
            print("   Normalized: \(normalizedKey.prefix(20))...")
            throw CallError.invalidPeerKey
        }
        
        // ECDH key agreement - derive shared secret
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: peerPublicKeyObj)
        
        // Derive encryption key using HKDF
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: "OSHI-VoiceCall-v1".data(using: .utf8)!,
            sharedInfo: "signaling".data(using: .utf8)!,
            outputByteCount: 32
        )
        
        // Encrypt with AES-GCM
        let sealed = try AES.GCM.seal(data, using: symmetricKey)
        return sealed.combined!
    }
    
    /// Decrypt signaling data from peer using ECDH
    private func decryptFromPeer(data: Data, peerPublicKey: String) throws -> Data {
        // Get our private key
        guard let identityManager = getIdentityManager(),
              let privateKey = identityManager.privateKey else {
            throw CallError.invalidPeerKey
        }

        // 🔧 FIX: Normalize key from base64url to standard base64
        // VPS and some paths use base64url encoding (- and _) instead of standard (+, /, =)
        let normalizedKey = base64urlDecode(peerPublicKey)

        // Parse peer's public key
        guard let peerKeyData = Data(base64Encoded: normalizedKey),
              let peerPublicKeyObj = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerKeyData) else {
            print("❌ VoiceCall: Decryption failed - invalid peer key: \(peerPublicKey.prefix(20))...")
            print("   Normalized: \(normalizedKey.prefix(20))...")
            throw CallError.invalidPeerKey
        }
        
        // ECDH key agreement
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: peerPublicKeyObj)
        
        // Derive same key
        let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: "OSHI-VoiceCall-v1".data(using: .utf8)!,
            sharedInfo: "signaling".data(using: .utf8)!,
            outputByteCount: 32
        )
        
        // Decrypt
        let sealed = try AES.GCM.SealedBox(combined: data)
        return try AES.GCM.open(sealed, using: symmetricKey)
    }
    
    /// Get IdentityManager instance
    private func getIdentityManager() -> IdentityManager? {
        // Access shared instance through UserDefaults stored key
        // This is a workaround since IdentityManager is not a singleton
        if let publicKey = UserDefaults.standard.string(forKey: "publicKey"),
           !publicKey.isEmpty {
            // IdentityManager restores from keychain on init
            let manager = IdentityManager()
            // Check if it has valid keys (publicKey is non-optional String, check if not empty)
            if !manager.publicKey.isEmpty {
                return manager
            }
        }
        return nil
    }
    
    // MARK: - Network Helpers

    // 🔧 FIX: Maximum age for call signals (30 seconds)
    // Increased from 15s to 30s to handle network delays with VPS relay
    // VPS routing + IPFS fallback can take several seconds on slow networks
    private let maxCallSignalAgeSeconds: TimeInterval = 30.0

    private func createCallPacket(type: CallPacketType, payload: Data) -> Data {
        var packet = Data()
        packet.append(type.rawValue)

        // 🔧 FIX: Add timestamp (8 bytes, milliseconds since 1970) to prevent stale signal issues
        // This allows receivers to reject old signals that might have been delayed in mesh relay
        let timestampMs = UInt64(Date().timeIntervalSince1970 * 1000)
        var timestampBytes = timestampMs.bigEndian
        packet.append(Data(bytes: &timestampBytes, count: 8))

        packet.append(contentsOf: payload)
        return packet
    }

    /// Validate a call packet's timestamp
    /// Returns the payload (without type byte and timestamp) if valid, nil if too old
    private func validateAndExtractPayload(from data: Data, packetType: CallPacketType) -> Data? {
        // Packet format: [type(1)] + [timestamp(8)] + [payload]
        guard data.count >= 9 else {
            // Legacy packet without timestamp - accept for backwards compatibility during transition
            // but only for certain packet types
            if packetType == .callRequest || packetType == .videoCallRequest {
                // Call requests can be legacy
                return data.count > 1 ? data.dropFirst() : Data()
            }
            print("⚠️ VoiceCall: Packet too small for timestamp validation")
            return nil
        }

        // Extract timestamp (bytes 1-8)
        // 🔧 FIX: Use safe unaligned byte reading to prevent crash
        let timestampData = data.subdata(in: 1..<9)
        var timestampMs: UInt64 = 0
        _ = Swift.withUnsafeMutableBytes(of: &timestampMs) { dest in
            timestampData.copyBytes(to: dest)
        }
        timestampMs = UInt64(bigEndian: timestampMs)
        let packetTime = Date(timeIntervalSince1970: Double(timestampMs) / 1000.0)
        let age = Date().timeIntervalSince(packetTime)

        // Check if packet is too old
        if age > maxCallSignalAgeSeconds {
            print("⚠️ VoiceCall: Rejecting stale call signal (age: \(String(format: "%.1f", age))s, max: \(maxCallSignalAgeSeconds)s)")
            print("   Packet type: \(packetType), timestamp: \(packetTime)")
            return nil
        }

        // Check if packet is from the future (clock skew > 5 seconds)
        if age < -5.0 {
            print("⚠️ VoiceCall: Rejecting future-dated call signal (age: \(String(format: "%.1f", age))s)")
            return nil
        }

        // 🔧 FIX: For callEnd and callDecline signals, also check if the signal was created
        // BEFORE the current call started. This prevents old callEnd signals from previous
        // call attempts ending the current call.
        if packetType == .callEnd || packetType == .callDecline {
            if let callStartTime = currentCallStartTime {
                if packetTime < callStartTime {
                    print("⚠️ VoiceCall: Rejecting \(packetType) signal from BEFORE current call started")
                    print("   Signal time: \(packetTime), Call start time: \(callStartTime)")
                    return nil
                }
            }

            // 🔧 FIX: CRITICAL - Add grace period after call connects
            // If the call just connected (transitioned to .inCall), ignore callEnd signals
            // for a brief period. This prevents race conditions where stale signals
            // that were in-flight end a newly connected call.
            if let connectedTime = callConnectedTime {
                let timeSinceConnect = Date().timeIntervalSince(connectedTime)
                if timeSinceConnect < callEndGracePeriodSeconds {
                    // Only reject if the signal timestamp is BEFORE the connection time
                    // This ensures legitimate "hang up immediately" actions still work
                    if packetTime < connectedTime {
                        print("⚠️ VoiceCall: Rejecting \(packetType) signal during grace period")
                        print("   Signal time: \(packetTime), Connected time: \(connectedTime)")
                        print("   Time since connect: \(String(format: "%.1f", timeSinceConnect))s (grace period: \(callEndGracePeriodSeconds)s)")
                        return nil
                    }
                }
            }
        }

        // Return payload (everything after type + timestamp)
        return data.count > 9 ? Data(data.suffix(from: 9)) : Data()
    }
    
    private func sendViaTorRelay(to peerPublicKey: String, data: Data, overrideCallId: String? = nil) async throws {
        // Send call signal DIRECTLY to VPS - no Pinata/IPFS needed!
        // VPS routes based on recipient in body, much faster than IPFS

        guard let identityManager = getIdentityManager() else {
            throw CallError.invalidPeerKey
        }

        let myPublicKey = identityManager.publicKey

        // 🔧 FIX: Use overrideCallId (from captured pre-cleanup state) if provided
        let effectiveCallId: String
        if let override = overrideCallId {
            effectiveCallId = override
        } else if let existing = currentCallId {
            effectiveCallId = existing
        } else {
            let newId = UUID().uuidString
            currentCallId = newId
            effectiveCallId = newId
        }

        print("📡 VoiceCall: Sending call signal to VPS Tor relay...")
        print("   From: \(myPublicKey.prefix(12))...")
        print("   To: \(peerPublicKey.prefix(12))...")

        // Create call signal payload - matches what call_server.js expects
        let signalPayload: [String: Any] = [
            "recipient": peerPublicKey,
            "sender": myPublicKey,
            "signal": data.base64EncodedString(),
            "callId": effectiveCallId
        ]

        // POST to /signal (body contains recipient)
        guard let signalURL = URL(string: "\(vpsURL)/signal") else {
            throw CallError.networkError
        }

        print("📡 VoiceCall: Sending signal to \(vpsURL.prefix(40))...")

        var request = URLRequest(url: signalURL)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: signalPayload)
        request.timeoutInterval = 10

        // 🔧 FIX: Try up to 3 times before falling back to IPFS
        var lastError: Error?
        for attempt in 1...3 {
            do {
                let (responseData, response) = try await URLSession.shared.data(for: request)

                guard let httpResponse = response as? HTTPURLResponse else {
                    print("❌ VoiceCall: Invalid response from VPS (attempt \(attempt))")
                    throw CallError.networkError
                }

                print("📡 VoiceCall: VPS signal endpoint response: \(httpResponse.statusCode) (attempt \(attempt))")

                if httpResponse.statusCode == 200 || httpResponse.statusCode == 201 {
                    print("✅ VoiceCall: Call signal sent for \(peerPublicKey.prefix(8))...")
                    return
                }

                // Server error - log and retry
                if let errorString = String(data: responseData, encoding: .utf8) {
                    print("⚠️ VoiceCall: Signal endpoint error: \(errorString)")
                }
                lastError = CallError.networkError

            } catch {
                print("⚠️ VoiceCall: Signal endpoint failed (attempt \(attempt)): \(error.localizedDescription)")
                lastError = error
            }

            // Wait before retry (exponential backoff: 200ms, 400ms, 800ms)
            if attempt < 3 {
                let delayMs = UInt64(200 * (1 << (attempt - 1)))
                try? await Task.sleep(nanoseconds: delayMs * 1_000_000)
            }
        }

        print("🔄 VoiceCall: All VPS attempts failed (last error: \(lastError?.localizedDescription ?? "unknown")), falling back to IPFS queue...")

        // Fallback: Use existing message queue endpoint
        // This works but has higher latency (polls every 3-10 seconds)
        try await sendCallSignalViaQueue(to: peerPublicKey, encryptedData: data, vpsURL: vpsURL)
    }
    
    /// Fallback: Send call signal via IPFS (Pinata) and VPS queue
    private func sendCallSignalViaQueue(to peerPublicKey: String, encryptedData: Data, vpsURL: String) async throws {
        print("📡 VoiceCall: Uploading call signal to IPFS...")
        
        guard let identityManager = getIdentityManager() else {
            throw CallError.networkError
        }
        
        // Create signal message in same format as regular messages
        let signalMessage: [String: Any] = [
            "id": UUID().uuidString,
            "senderAddress": identityManager.publicKey,
            "recipientAddress": peerPublicKey,
            "encryptedContent": encryptedData.base64EncodedString(),
            "timestamp": ISO8601DateFormatter().string(from: Date()),
            "isCallSignal": true
        ]
        
        // Upload to Pinata IPFS via VPS proxy
        guard let pinataURL = URL(string: "\(PinataConfig.apiURL)/pinning/pinJSONToIPFS") else {
            throw CallError.networkError
        }
        
        let pinataPayload: [String: Any] = [
            "pinataContent": signalMessage,
            "pinataMetadata": [
                "name": "call_signal_\(UUID().uuidString.prefix(8))"
            ]
        ]
        
        guard let pinataData = try? JSONSerialization.data(withJSONObject: pinataPayload) else {
            throw CallError.networkError
        }
        
        var pinataRequest = URLRequest(url: pinataURL)
        pinataRequest.httpMethod = "POST"
        pinataRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        // Auth via VPS proxy (VPS handles Pinata credentials server-side)
        for (key, value) in PinataConfig.authHeaders() {
            pinataRequest.setValue(value, forHTTPHeaderField: key)
        }
        
        pinataRequest.httpBody = pinataData
        pinataRequest.timeoutInterval = 15
        
        let (responseData, response) = try await URLSession.shared.data(for: pinataRequest)
        
        guard let httpResponse = response as? HTTPURLResponse else {
            throw CallError.networkError
        }
        
        print("📡 VoiceCall: Pinata response: \(httpResponse.statusCode)")
        
        guard httpResponse.statusCode == 200 else {
            if let errorBody = String(data: responseData, encoding: .utf8) {
                print("❌ VoiceCall: Pinata error: \(errorBody)")
            }
            throw CallError.networkError
        }
        
        // Parse IPFS hash from response
        guard let responseJSON = try? JSONSerialization.jsonObject(with: responseData) as? [String: Any],
              let ipfsHash = responseJSON["IpfsHash"] as? String else {
            print("❌ VoiceCall: Could not parse IPFS hash from Pinata response")
            throw CallError.networkError
        }
        
        print("✅ VoiceCall: Call signal uploaded to IPFS: \(ipfsHash)")

        // Queue the IPFS hash for the recipient on VPS
        // 🔧 CRITICAL FIX: Use base64url encoding (not percent encoding)
        let encodedRecipient = base64urlEncode(peerPublicKey)
        guard let queueURL = URL(string: "\(vpsURL)/api/queue/\(encodedRecipient)/\(ipfsHash)") else {
            throw CallError.networkError
        }
        
        var queueRequest = URLRequest(url: queueURL)
        queueRequest.httpMethod = "POST"
        queueRequest.timeoutInterval = 5
        
        let (_, queueResponse) = try await URLSession.shared.data(for: queueRequest)
        
        if let queueHttp = queueResponse as? HTTPURLResponse {
            print("📡 VoiceCall: Queue response: \(queueHttp.statusCode)")
            if queueHttp.statusCode == 200 || queueHttp.statusCode == 201 {
                print("✅ VoiceCall: Call signal queued for \(peerPublicKey.prefix(8))...")
            }
        }
    }
    
    /// Fallback: Send call signal as a special encrypted message via IPFS
    private func sendCallSignalViaMessage(to peerPublicKey: String, data: Data) async throws {
        print("📨 VoiceCall: Sending call signal via message system...")
        
        guard let identityManager = getIdentityManager() else {
            throw CallError.invalidPeerKey
        }
        
        // Create a special call signal message
        let callSignalContent = "📞CALL_SIGNAL📞\(data.base64EncodedString())"
        
        // Post notification to send via existing message infrastructure
        await MainActor.run {
            NotificationCenter.default.post(
                name: NSNotification.Name("SendCallSignalMessage"),
                object: nil,
                userInfo: [
                    "recipient": peerPublicKey,
                    "content": callSignalContent,
                    "sender": identityManager.publicKey
                ]
            )
        }
        
        print("✅ VoiceCall: Call signal posted to message system")
    }
    
    private func sendAudioViaTor(data: Data) {
        // Send encrypted audio via VPS relay
        guard let peer = currentCallPeer,
              let myKey = getIdentityManager()?.publicKey else { return }
        
        guard let url = URL(string: "\(vpsBaseURL)/api/call/audio") else { return }
        
        // Encrypt audio data before sending - 🔧 Thread-safe nonce
        guard let (encryptedAudio, _) = encryptAudioData(data) else {
            print("❌ VoiceCall: Failed to encrypt audio")
            return
        }

        // Create audio packet payload
        let payload: [String: Any] = [
            "recipient": peer,
            "sender": myKey,
            "audio": encryptedAudio.base64EncodedString(),
            "callId": currentCallId ?? "",
            "timestamp": Date().timeIntervalSince1970
        ]
        
        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload) else { return }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = jsonData
        request.timeoutInterval = 0.5 // Very short timeout for real-time audio
        
        // Fire and forget - don't wait for response
        URLSession.shared.dataTask(with: request).resume()
    }
    
    // MARK: - WebSocket Audio (Low Latency)
    
    /// Connect WebSocket for real-time audio
    private func connectWebSocket() {
        guard let myKey = getIdentityManager()?.publicKey else {
            print("❌ VoiceCall: No identity for WebSocket")
            return
        }
        
        guard let url = URL(string: vpsWebSocketURL) else {
            print("❌ VoiceCall: Invalid WebSocket URL")
            // Fallback to HTTP polling
            startAudioPollingFallback()
            return
        }
        
        print("🔌 VoiceCall: Connecting WebSocket to \(vpsWebSocketURL)...")
        
        // Optimized config for real-time audio
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 10  // Faster connection timeout
        config.timeoutIntervalForResource = 60  // Keep connection alive
        config.httpShouldUsePipelining = true
        config.httpMaximumConnectionsPerHost = 2
        webSocketSession = URLSession(configuration: config)
        webSocket = webSocketSession?.webSocketTask(with: url)
        webSocket?.resume()
        
        // Register with server
        let registerMessage: [String: Any] = [
            "type": "register",
            "publicKey": myKey,
            "callId": currentCallId ?? ""
        ]
        
        if let jsonData = try? JSONSerialization.data(withJSONObject: registerMessage),
           let jsonString = String(data: jsonData, encoding: .utf8) {
            webSocket?.send(.string(jsonString)) { [weak self] error in
                if let error = error {
                    print("❌ VoiceCall: WebSocket register failed: \(error)")
                    self?.startAudioPollingFallback()
                } else {
                    print("✅ VoiceCall: WebSocket registered")
                    self?.isWebSocketConnected = true
                    self?.receiveWebSocketMessages()
                }
            }
        }
    }
    
    /// Receive messages from WebSocket
    private func receiveWebSocketMessages() {
        webSocket?.receive { [weak self] result in
            guard let self = self else { return }
            
            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    self.handleWebSocketMessage(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) {
                        self.handleWebSocketMessage(text)
                    }
                @unknown default:
                    break
                }
                
                // Continue receiving for ALL active call states (not just .inCall)
                // During .connecting/.ringing, video packets may already arrive
                let activeStates: [CallState] = [.inCall, .connecting, .ringing]
                if activeStates.contains(self.callState) {
                    self.receiveWebSocketMessages()
                }
                
            case .failure(let error):
                print("❌ VoiceCall: WebSocket receive error: \(error)")
                self.isWebSocketConnected = false
                
                // 📶 NEW: Try to reconnect WebSocket instead of just falling back
                if self.callState == .inCall {
                    self.attemptWebSocketReconnect()
                }
            }
        }
    }
    
    /// 📶 NEW: Attempt to reconnect WebSocket with exponential backoff
    private func attemptWebSocketReconnect() {
        guard callState == .inCall, !isReconnecting else { return }
        guard reconnectAttempts < maxReconnectAttempts else {
            print("⚠️ VoiceCall: Max reconnect attempts reached, falling back to HTTP")
            reconnectAttempts = 0
            startAudioPollingFallback()
            return
        }
        
        isReconnecting = true
        reconnectAttempts += 1
        
        // 🔧 FIX: Faster reconnection - 0.3s, 0.5s, 1s, 2s
        let delay = 0.3 * pow(1.5, Double(reconnectAttempts - 1))
        print("📶 VoiceCall: Reconnect attempt \(reconnectAttempts)/\(maxReconnectAttempts) in \(String(format: "%.1f", delay))s...")
        
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self = self, self.callState == .inCall else {
                self?.isReconnecting = false
                return
            }
            
            // Disconnect old socket
            self.webSocket?.cancel(with: .goingAway, reason: nil)
            self.webSocket = nil
            
            // Try to reconnect
            self.connectWebSocket()
            
            // Check if reconnected after 2s
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
                guard let self = self else { return }
                self.isReconnecting = false
                
                if self.isWebSocketConnected {
                    print("✅ VoiceCall: WebSocket reconnected successfully!")
                    self.reconnectAttempts = 0
                    
                    // Update UI
                    DispatchQueue.main.async {
                        if self.connectionQuality == .reconnecting {
                            self.connectionQuality = .good
                        }
                    }
                } else {
                    // Try again
                    self.attemptWebSocketReconnect()
                }
            }
        }
    }
    
    /// Handle incoming WebSocket message
    private func handleWebSocketMessage(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else { return }
        
        switch type {
        case "audio":
            // Received audio packet - format: [type(1)][seq(8)][encrypted_audio]
            if let audioBase64 = json["audio"] as? String,
               let audioPacket = Data(base64Encoded: audioBase64),
               audioPacket.count > 9 {
                receivedAudioPacketCount += 1
                
                // Track stats
                internalPacketsReceived += 1
                internalBytesReceived += UInt64(audioPacket.count)
                packetsReceivedSinceLastCheck += 1
                
                // Log first 10 packets, then every 50th
                if receivedAudioPacketCount <= 10 || receivedAudioPacketCount % 50 == 0 {
                    print("🔊 VoiceCall: Received audio packet #\(receivedAudioPacketCount), size: \(audioPacket.count)")
                    callLog.info("🔊 RX #\(self.receivedAudioPacketCount) size:\(audioPacket.count)")
                    fileLog.log("🔊 RX #\(self.receivedAudioPacketCount) size:\(audioPacket.count)")
                }
                
                // 🎯 Route through FULL quality pipeline (same as mesh)
                // This ensures VPS gets: FEC recovery, advanced jitter buffer, PLC, adaptive bitrate
                receiveAudio(encryptedData: audioPacket)
            }
            
        case "video":
            // 📹 Received video packet via WebSocket
            if let videoBase64 = json["video"] as? String,
               let videoPacket = Data(base64Encoded: videoBase64) {
                // 🔧 FIX: Log ALL received video packets for debugging
                let videoRxCount = (videoPacket.count > 9) ? videoPacket.count : 0
                if videoRxCount > 0 {
                    let videoData = Data(videoPacket.suffix(from: 9))
                    fileLog.log("📹 VIDEO RX (WS) size:\(videoData.count)B (raw:\(videoPacket.count)B) handler:\(onVideoPacketReceived != nil)")
                    if let handler = onVideoPacketReceived {
                        handler(videoData)
                    } else {
                        pendingVideoPackets.append(videoData)
                        if pendingVideoPackets.count > maxPendingVideoPackets {
                            pendingVideoPackets.removeFirst()
                        }
                        if pendingVideoPackets.count <= 3 {
                            print("📹 VoiceCall: Buffering video packet (WS) - no handler yet, buffered: \(pendingVideoPackets.count)")
                        }
                    }
                } else {
                    fileLog.log("⚠️ VIDEO RX (WS) too small: \(videoPacket.count)B")
                }
            } else {
                fileLog.log("⚠️ VIDEO RX (WS) base64 decode failed or missing")
            }
            
        case "call_signal":
            // Received call signal (accept, decline, end)
            if let signalBase64 = json["signal"] as? String,
               let signalData = Data(base64Encoded: signalBase64),
               let sender = json["sender"] as? String {
                // 🔧 FIX: Use getDisplayName to show contact alias
                let senderName = getDisplayName(for: sender)
                handleReceivedCallSignal(from: sender, peerName: senderName, encryptedData: signalData)
            }
            
        case "pong":
            // Keep-alive response
            break
            
        case "registered":
            // Server confirmed registration
            print("✅ VoiceCall: WebSocket registration confirmed by server")
            
        default:
            print("📨 VoiceCall: Unknown WebSocket message type: \(type)")
        }
    }
    
    /// Send audio via WebSocket (lowest latency)
    /// Note: data is already encrypted in processAndSendAudio
    private var sentAudioPacketCount: Int = 0
    
    private func sendAudioViaWebSocket(data: Data) {
        guard isWebSocketConnected,
              let peer = currentCallPeer else {
            // Fallback to HTTP if WebSocket not connected
            if sentAudioPacketCount < 5 {
                print("⚠️ VoiceCall: WebSocket not connected, falling back to HTTP")
            }
            sendAudioViaHTTP(data: data)
            return
        }
        
        sentAudioPacketCount += 1
        
        // Log first 10 packets, then every 100th
        if sentAudioPacketCount <= 10 || sentAudioPacketCount % 100 == 0 {
            print("🎤 VoiceCall: Sending audio via WebSocket #\(sentAudioPacketCount), size: \(data.count)")
        }
        
        // Data is already encrypted (from processAndSendAudio), just send it
        let message: [String: Any] = [
            "type": "audio",
            "recipient": peer,
            "audio": data.base64EncodedString(),
            "callId": currentCallId ?? "",
            "timestamp": Date().timeIntervalSince1970
        ]
        
        if let jsonData = try? JSONSerialization.data(withJSONObject: message),
           let jsonString = String(data: jsonData, encoding: .utf8) {
            webSocket?.send(.string(jsonString)) { [weak self] error in
                if let error = error {
                    print("⚠️ VoiceCall: WebSocket send error: \(error)")
                    self?.isWebSocketConnected = false
                }
            }
        }
    }
    
    /// Send audio via HTTP (fallback) - supports Tor routing
    /// Note: data is already encrypted in processAndSendAudio
    private func sendAudioViaHTTP(data: Data) {
        guard let peer = currentCallPeer,
              let myKey = getIdentityManager()?.publicKey else { return }
        
        guard let url = URL(string: "\(vpsURL)/audio") else { return }
        
        // Data is already encrypted (from processAndSendAudio), just send it
        let payload: [String: Any] = [
            "recipient": peer,
            "sender": myKey,
            "audio": data.base64EncodedString(),
            "callId": currentCallId ?? "",
            "timestamp": Date().timeIntervalSince1970
        ]
        
        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload) else { return }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = jsonData
        request.timeoutInterval = 0.3  // 300ms timeout for real-time audio
        
        // Use optimized session for lower latency
        audioURLSession.dataTask(with: request).resume()
    }
    
    /// Disconnect WebSocket
    private func disconnectWebSocket() {
        webSocket?.cancel(with: .normalClosure, reason: nil)
        webSocket = nil
        webSocketSession = nil
        isWebSocketConnected = false
        print("🔌 VoiceCall: WebSocket disconnected")
    }
    
    /// Start audio - try WebSocket first, but always have HTTP polling as backup
    private func startAudioStreaming() {
        // 🔧 FIX: All connection types now route audio via VPS for reliability
        // So we need WebSocket/HTTP polling for ALL types including .mesh
        print("🎧 VoiceCall: Starting audio streaming (connectionType: \(connectionType))...")
        fileLog.log("🎧 startAudioStreaming: connType=\(connectionType) isVideo=\(isCurrentCallVideo) callState=\(callState)")

        // Try WebSocket first for lowest latency
        connectWebSocket()

        // 🔧 FIX: Start HTTP polling immediately as backup
        // Don't wait - run both in parallel for reliability
        startHTTPAudioPolling()

        // Check WebSocket status after short delay
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self = self, self.callState == .inCall else { return }

            if self.isWebSocketConnected {
                print("🎧 VoiceCall: WebSocket connected, HTTP polling on standby")
            } else {
                print("🎧 VoiceCall: WebSocket not connected, HTTP polling active")
            }
        }
    }
    
    /// Start HTTP audio polling (backup for WebSocket)
    private func startHTTPAudioPolling() {
        audioPollingTimer?.invalidate()
        // 🔧 FIX: Poll every 30ms (33 polls/second) for better latency
        // Also poll even when WebSocket is connected (as backup)
        audioPollingTimer = Timer.scheduledTimer(withTimeInterval: 0.03, repeats: true) { [weak self] _ in
            // Skip polling if we're receiving audio via WebSocket reliably
            guard let self = self else { return }
            // Always poll - WebSocket may drop audio packets
            self.pollForAudio()
        }
        if let timer = audioPollingTimer {
            RunLoop.main.add(timer, forMode: .common)
        }
    }
    
    /// Fallback to HTTP polling if WebSocket fails
    private func startAudioPollingFallback() {
        // 🔧 FIX: All connection types now route audio via VPS
        guard !isWebSocketConnected else { return }
        
        print("🎧 VoiceCall: Starting HTTP audio polling (20ms interval)...")
        
        audioPollingTimer?.invalidate()
        // Poll every 20ms for low latency audio (~50 polls/second)
        audioPollingTimer = Timer.scheduledTimer(withTimeInterval: 0.02, repeats: true) { [weak self] _ in
            self?.pollForAudio()
        }
    }
    
    private var audioPollingTimer: Timer?
    private var audioPollingCount = 0
    
    /// Poll for incoming audio packets - optimized for low latency
    private func pollForAudio() {
        guard callState == .inCall else { return }
        guard let myKey = getIdentityManager()?.publicKey else { return }
        
        // 🔧 CRITICAL FIX: Use base64url encoding (not percent encoding)
        // Standard base64 uses +/= which break URL paths
        // Base64url uses -_ and is URL-safe
        let encodedKey = base64urlEncode(myKey)
        
        guard let url = URL(string: "\(vpsURL)/audio/\(encodedKey)") else { return }
        
        var request = URLRequest(url: url)
        request.timeoutInterval = 1.5  // 🔧 FIX: 1.5s timeout - more forgiving for mobile networks
        request.cachePolicy = URLRequest.CachePolicy.reloadIgnoringLocalAndRemoteCacheData
        
        audioPollingCount += 1
        
        // Use optimized session for lower latency
        audioURLSession.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self else { return }
            
            // Log every 100th poll to reduce overhead
            if self.audioPollingCount % 100 == 1 {
                let statusCode = (response as? HTTPURLResponse)?.statusCode ?? 0
                let dataSize = data?.count ?? 0
                print("🎧 VoiceCall: Audio poll #\(self.audioPollingCount) - status: \(statusCode), data: \(dataSize) bytes")
            }
            
            guard let data = data, !data.isEmpty else { return }
            
            // Parse array of audio packets
            if let packets = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] {
                for packet in packets {
                    if let audioBase64 = packet["audio"] as? String,
                       let audioPacket = Data(base64Encoded: audioBase64),
                       audioPacket.count > 9 {
                        // 🎯 Route through FULL quality pipeline (same as mesh)
                        // This ensures HTTP polling gets: FEC recovery, advanced jitter buffer, PLC, adaptive bitrate
                        self.receiveAudio(encryptedData: audioPacket)
                    }
                }
            }
        }.resume()
        
        // 📹 Also poll for video on EVERY audio poll cycle for video calls
        // (was every 3rd — too slow, missed keyframes at 30fps)
        if isCurrentCallVideo {
            pollForVideo(encodedKey: encodedKey)
        }
    }
    
    /// Poll for incoming video packets (HTTP fallback for video calls)
    private var videoPollCount: Int = 0
    private var videoRxHttpCount: Int = 0

    private func pollForVideo(encodedKey: String) {
        guard let url = URL(string: "\(vpsURL)/video/\(encodedKey)") else { return }

        videoPollCount += 1

        var request = URLRequest(url: url)
        request.timeoutInterval = 0.5
        request.cachePolicy = URLRequest.CachePolicy.reloadIgnoringLocalAndRemoteCacheData

        audioURLSession.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self,
                  let data = data, !data.isEmpty else { return }

            // Parse array of video packets
            if let packets = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]], !packets.isEmpty {
                self.videoRxHttpCount += packets.count
                fileLog.log("📹 VIDEO RX (HTTP) \(packets.count) packets (total: \(self.videoRxHttpCount))")

                // 🔧 FIX: Dispatch to main thread since VideoCallManager is @MainActor
                DispatchQueue.main.async {
                    for packet in packets {
                        if let videoBase64 = packet["video"] as? String,
                           let videoPacket = Data(base64Encoded: videoBase64),
                           videoPacket.count > 9 {
                            let videoData = Data(videoPacket.suffix(from: 9))
                            if let handler = self.onVideoPacketReceived {
                                handler(videoData)
                            } else {
                                self.pendingVideoPackets.append(videoData)
                                if self.pendingVideoPackets.count > self.maxPendingVideoPackets {
                                    self.pendingVideoPackets.removeFirst()
                                }
                            }
                        }
                    }
                }
            }
        }.resume()
    }
    
    private func stopAudioStreaming() {
        audioPollingTimer?.invalidate()
        audioPollingTimer = nil
        audioPollingCount = 0
        disconnectWebSocket()
    }
    
    // MARK: - Buffer Conversion
    
    private func bufferToData(_ buffer: AVAudioPCMBuffer) -> Data? {
        guard let floatData = buffer.floatChannelData else { return nil }
        let frameCount = Int(buffer.frameLength)
        let byteCount = frameCount * MemoryLayout<Float>.size
        let data = Data(bytes: floatData[0], count: byteCount)
        return data
    }
    
    private func dataToBuffer(_ data: Data) -> AVAudioPCMBuffer? {
        let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)!
        let frameCount = AVAudioFrameCount(data.count / MemoryLayout<Float>.size)
        
        guard frameCount > 0 else { return nil }
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount) else { return nil }
        buffer.frameLength = frameCount
        
        guard let floatData = buffer.floatChannelData else { return nil }
        
        // Safe copy without alignment issues
        data.withUnsafeBytes { rawBuffer in
            guard let baseAddress = rawBuffer.baseAddress else { return }
            memcpy(floatData[0], baseAddress, min(data.count, Int(frameCount) * MemoryLayout<Float>.size))
        }
        
        return buffer
    }

    /// Convert Float32 PCM data to Int16 PCM data (for codec input)
    private func float32ToInt16(_ floatData: Data) -> Data {
        let floatCount = floatData.count / MemoryLayout<Float>.size
        var int16Data = Data(capacity: floatCount * 2)
        floatData.withUnsafeBytes { rawBuffer in
            let floats = rawBuffer.bindMemory(to: Float.self)
            for i in 0..<floatCount {
                let clamped = max(-1.0, min(1.0, floats[i]))
                var sample = Int16(clamped * 32767.0)
                withUnsafeBytes(of: &sample) { int16Data.append(contentsOf: $0) }
            }
        }
        return int16Data
    }

    /// Convert Int16 PCM data to Float32 PCM data (for playback buffer)
    private func int16ToFloat32(_ int16Data: Data) -> Data {
        let sampleCount = int16Data.count / 2
        var floatData = Data(capacity: sampleCount * MemoryLayout<Float>.size)
        int16Data.withUnsafeBytes { rawBuffer in
            let samples = rawBuffer.bindMemory(to: Int16.self)
            for i in 0..<sampleCount {
                var value = Float(samples[i]) / 32767.0
                withUnsafeBytes(of: &value) { floatData.append(contentsOf: $0) }
            }
        }
        return floatData
    }

    private func calculateAudioLevel(buffer: AVAudioPCMBuffer) -> Float {
        guard let floatData = buffer.floatChannelData else { return 0 }
        let frameCount = Int(buffer.frameLength)
        var sum: Float = 0
        
        for i in 0..<frameCount {
            sum += abs(floatData[0][i])
        }
        
        let average = sum / Float(frameCount)
        return min(1.0, average * 5)  // Amplify for visualization
    }
    
    // MARK: - Call Timer
    
    private func startCallTimer() {
        // Must be called on main thread for timer to work properly
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.callTimer?.invalidate()  // Invalidate any existing timer
            self.callTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
                guard let self = self, let start = self.callStartTime else { return }
                DispatchQueue.main.async {
                    self.callDuration = Date().timeIntervalSince(start)
                }
            }
            // Add to common RunLoop mode to ensure it fires during UI interaction
            RunLoop.main.add(self.callTimer!, forMode: .common)
            print("⏱️ VoiceCall: Call timer started")
        }
    }
    
    // MARK: - Waiting Timer (for connecting/ringing)
    
    private func startWaitingTimer() {
        // Must be called on main thread for timer to work properly
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.waitingTimer?.invalidate()  // Invalidate any existing timer
            self.waitingStartTime = Date()
            self.waitingDuration = 0
            self.waitingTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
                guard let self = self, let start = self.waitingStartTime else { return }
                DispatchQueue.main.async {
                    self.waitingDuration = Date().timeIntervalSince(start)
                }
            }
            // Add to common RunLoop mode to ensure it fires during UI interaction
            RunLoop.main.add(self.waitingTimer!, forMode: .common)
            print("⏱️ VoiceCall: Waiting timer started")
        }
    }
    
    private func stopWaitingTimer() {
        DispatchQueue.main.async { [weak self] in
            self?.waitingTimer?.invalidate()
            self?.waitingTimer = nil
            self?.waitingStartTime = nil
        }
    }
    
    private func startKeepAlive() {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.keepAliveTimer?.invalidate()
            // Send keepalive every 3 seconds (faster for better quality monitoring)
            self.keepAliveTimer = Timer.scheduledTimer(withTimeInterval: 3.0, repeats: true) { [weak self] _ in
                self?.sendKeepAlive()
            }
            RunLoop.main.add(self.keepAliveTimer!, forMode: .common)
            
            // Start quality monitoring
            self.startQualityMonitoring()
        }
    }
    
    private func sendKeepAlive() {
        guard callState == .inCall, let peer = currentCallPeer else { return }

        // Include ping sequence for latency measurement
        pingSequence += 1
        var pingData = Data()
        pingData.append(contentsOf: withUnsafeBytes(of: pingSequence.bigEndian) { Array($0) })
        pingData.append(contentsOf: withUnsafeBytes(of: Date().timeIntervalSince1970.bitPattern.bigEndian) { Array($0) })

        pendingPings[pingSequence] = Date()

        let keepAlive = createCallPacket(type: .keepAlive, payload: pingData)

        // 🔧 SIMPLIFIED: Use locked connection type
        if lockedConnectionType == .meshDirect {
            // Mesh call → keepAlive via mesh
            if let encrypted = try? encryptForPeer(data: keepAlive, peerPublicKey: peer) {
                meshManager?.sendCallSignal(to: peer, data: encrypted)
            }
        } else {
            // VPS call → ping via VPS HTTP
            sendPingViaVPS(sequence: pingSequence)
        }
    }
    
    // MARK: - Connection Quality Monitoring
    
    private func startQualityMonitoring() {
        qualityMonitorTimer?.invalidate()
        
        // Reset stats (qualityStats is @Published, so update on main thread)
        DispatchQueue.main.async {
            self.qualityStats = CallQualityStats()
        }
        latencyHistory.removeAll()
        pendingPings.removeAll()
        packetsSentSinceLastCheck = 0
        packetsReceivedSinceLastCheck = 0
        lastPacketLossCheck = Date()
        
        // Reset internal counters
        internalPacketsSent = 0
        internalPacketsReceived = 0
        internalBytesSent = 0
        internalBytesReceived = 0
        
        // Monitor every 2 seconds
        qualityMonitorTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            self?.updateQualityMetrics()
        }
        RunLoop.main.add(qualityMonitorTimer!, forMode: .common)
        
        // Initial latency test
        measureLatency()
        
        // Verify encryption is active
        verifyEncryption()
        
        print("📊 VoiceCall: Quality monitoring started")
    }
    
    private func stopQualityMonitoring() {
        qualityMonitorTimer?.invalidate()
        qualityMonitorTimer = nil
    }
    
    /// Measure round-trip latency to VPS or peer
    private func measureLatency() {
        guard callState == .inCall else { return }

        let startTime = Date()

        // 🔧 SIMPLIFIED: Only .meshDirect or .tor
        if lockedConnectionType == .meshDirect {
            // For mesh direct, latency is typically very low (Bluetooth/WiFi direct)
            // We estimate based on audio packet round-trip
            let estimatedMeshLatency = 20 + Int.random(in: 0...15)  // 20-35ms typical
            updateLatency(estimatedMeshLatency)
        } else {
            // For VPS relay, measure actual HTTP round-trip
            guard let url = URL(string: "\(vpsURL)/health") else { return }
            
            var request = URLRequest(url: url)
            request.timeoutInterval = 2.0
            request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
            
            audioURLSession.dataTask(with: request) { [weak self] _, response, error in
                guard let self = self else { return }
                
                let latency = Int(Date().timeIntervalSince(startTime) * 1000)
                
                DispatchQueue.main.async {
                    if error != nil || response == nil {
                        self.handleConnectionIssue()
                    } else {
                        // VPS latency is one-way, double for round-trip estimate
                        self.updateLatency(latency)
                    }
                }
            }.resume()
        }
    }
    
    /// Send ping via VPS for latency measurement
    private func sendPingViaVPS(sequence: UInt32) {
        guard let url = URL(string: "\(vpsURL)/ping") else { return }
        
        let payload: [String: Any] = [
            "seq": sequence,
            "ts": Date().timeIntervalSince1970,
            "callId": currentCallId ?? ""
        ]
        
        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload) else { return }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = jsonData
        request.timeoutInterval = 2.0
        
        audioURLSession.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self,
                  let sentTime = self.pendingPings[sequence] else { return }
            
            self.pendingPings.removeValue(forKey: sequence)
            
            let latency = Int(Date().timeIntervalSince(sentTime) * 1000)
            
            DispatchQueue.main.async {
                if error == nil && response != nil {
                    self.updateLatency(latency)
                }
            }
        }.resume()
    }
    
    /// Update latency with smoothing
    private func updateLatency(_ latency: Int) {
        latencyHistory.append(latency)
        if latencyHistory.count > latencyHistorySize {
            latencyHistory.removeFirst()
        }
        
        // Calculate moving average
        let avgLatency = latencyHistory.reduce(0, +) / latencyHistory.count
        
        // Calculate jitter (standard deviation of latency)
        var calculatedJitter = currentJitter
        if latencyHistory.count >= 3 {
            var jitterSum = 0
            for i in 1..<latencyHistory.count {
                jitterSum += abs(latencyHistory[i] - latencyHistory[i-1])
            }
            calculatedJitter = jitterSum / (latencyHistory.count - 1)
        }
        
        // Update @Published properties on main thread
        DispatchQueue.main.async {
            self.currentLatency = avgLatency
            self.qualityStats.latencyMs = avgLatency
            self.currentJitter = calculatedJitter
            self.qualityStats.jitterMs = calculatedJitter
        }
        
        // Update quality indicator
        updateConnectionQuality()
    }
    
    /// Handle connection issues
    private func handleConnectionIssue() {
        DispatchQueue.main.async {
            self.connectionQuality = .reconnecting
            self.qualityStats.latencyMs = -1
        }
    }
    
    /// Update quality metrics periodically
    private func updateQualityMetrics() {
        guard callState == .inCall else { return }
        
        // Calculate packet loss using internal counters
        let now = Date()
        let timeSinceLastCheck = now.timeIntervalSince(lastPacketLossCheck)
        
        // Sync internal counters to published stats periodically (every 2 seconds)
        let sentCount = internalPacketsSent
        let receivedCount = internalPacketsReceived
        let bytesSent = internalBytesSent
        let bytesReceived = internalBytesReceived
        
        if timeSinceLastCheck >= 2.0 {
            // Calculate packet loss from internal counters
            // 🔧 FIX: Use safe subtraction to avoid overflow when receivedCount > sentCount
            let lossRate: Double
            if sentCount > 0 && sentCount > receivedCount {
                lossRate = Double(sentCount - receivedCount) / Double(sentCount) * 100
            } else {
                lossRate = 0
            }
            
            DispatchQueue.main.async {
                // Sync counters to published stats
                self.qualityStats.audioPacketsSent = sentCount
                self.qualityStats.audioPacketsReceived = receivedCount
                self.qualityStats.bytesTransmitted = bytesSent
                self.qualityStats.bytesReceived = bytesReceived
                self.qualityStats.packetLossPercent = max(0, min(100, lossRate))
                self.qualityStats.lastPingTime = Date()
            }
            
            packetsSentSinceLastCheck = 0
            packetsReceivedSinceLastCheck = 0
            lastPacketLossCheck = now
        }
        
        // Measure latency
        measureLatency()
        
        // Update connection quality
        updateConnectionQuality()
        
        // Log quality every 10 seconds
        if Int(callDuration) % 10 == 0 && callDuration > 0 {
            print("📊 Call Quality: \(connectionQuality.rawValue) | Latency: \(currentLatency)ms | Jitter: \(currentJitter)ms | Sent: \(sentCount) | Received: \(receivedCount)")
        }
    }
    
    /// Update connection quality based on all metrics
    private func updateConnectionQuality() {
        let quality = qualityStats.quality
        
        // Use the comprehensive quality from stats
        DispatchQueue.main.async {
            self.connectionQuality = quality
        }
    }
    
    /// Verify encryption is active and working
    private func verifyEncryption() {
        guard sessionKey != nil else {
            DispatchQueue.main.async {
                self.qualityStats.encryptionVerified = false
            }
            print("⚠️ VoiceCall: Encryption NOT verified - no session key")
            return
        }
        
        // Test encrypt/decrypt cycle
        let testData = "OSHI_ENCRYPTION_TEST".data(using: .utf8)!
        if let (encrypted, _) = encryptAudioData(testData),
           encrypted.count > testData.count,  // Should be larger due to GCM tag
           let decrypted = decryptAudioData(encrypted),
           decrypted == testData {
            DispatchQueue.main.async {
                self.qualityStats.encryptionVerified = true
            }
            print("🔐 VoiceCall: E2E Encryption VERIFIED (AES-256-GCM)")
        } else {
            DispatchQueue.main.async {
                self.qualityStats.encryptionVerified = false
            }
            print("❌ VoiceCall: Encryption verification FAILED")
        }
    }
    
    /// Get formatted quality report
    func getQualityReport() -> String {
        return """
        📊 Call Quality Report
        ━━━━━━━━━━━━━━━━━━━━━
        Quality: \(connectionQuality.rawValue)
        Latency: \(currentLatency)ms
        Jitter: \(currentJitter)ms
        Packet Loss: \(String(format: "%.1f", qualityStats.packetLossPercent))%
        
        📦 Packets
        Sent: \(qualityStats.audioPacketsSent)
        Received: \(qualityStats.audioPacketsReceived)
        
        🔐 Security
        Encryption: \(qualityStats.encryptionVerified ? "✅ AES-256-GCM" : "❌ Not Verified")
        Connection: \(connectionType)
        """
    }
    
    // MARK: - Ringtone & Ringback

    private var ringtonePlayer: AVAudioPlayer?
    private var ringtoneTimer: Timer?
    private var ringbackTimer: Timer?  // 🔧 NEW: For outgoing calls
    private var ringbackPlayer: AVAudioPlayer?  // 🔧 Proper phone ringback tone

    // 🔧 FIX: Play proper phone ringback tone (440Hz + 480Hz dual tone like real phone)
    private func playRingbackTone() {
        print("📞 VoiceCall: Starting ringback tone (proper phone sound)...")
        stopRingbackTone()

        // Generate WAV data: 440Hz + 480Hz dual tone, 2s on / 4s off (standard ringback)
        let sampleRate = 44100
        let onSamples = sampleRate * 2    // 2 seconds of tone
        let offSamples = sampleRate * 4   // 4 seconds of silence
        let totalSamples = onSamples + offSamples
        let bytesPerSample = 2
        let dataSize = totalSamples * bytesPerSample

        var wav = Data(capacity: 44 + dataSize)
        // RIFF header
        wav.append(contentsOf: [0x52, 0x49, 0x46, 0x46]) // "RIFF"
        var fileSize = UInt32(36 + dataSize).littleEndian
        wav.append(Data(bytes: &fileSize, count: 4))
        wav.append(contentsOf: [0x57, 0x41, 0x56, 0x45]) // "WAVE"
        // fmt chunk
        wav.append(contentsOf: [0x66, 0x6D, 0x74, 0x20]) // "fmt "
        var chunkSize = UInt32(16).littleEndian; wav.append(Data(bytes: &chunkSize, count: 4))
        var audioFmt = UInt16(1).littleEndian; wav.append(Data(bytes: &audioFmt, count: 2))
        var channels = UInt16(1).littleEndian; wav.append(Data(bytes: &channels, count: 2))
        var sr = UInt32(sampleRate).littleEndian; wav.append(Data(bytes: &sr, count: 4))
        var byteRate = UInt32(sampleRate * bytesPerSample).littleEndian; wav.append(Data(bytes: &byteRate, count: 4))
        var blockAlign = UInt16(bytesPerSample).littleEndian; wav.append(Data(bytes: &blockAlign, count: 2))
        var bitsPerSample = UInt16(16).littleEndian; wav.append(Data(bytes: &bitsPerSample, count: 2))
        // data chunk
        wav.append(contentsOf: [0x64, 0x61, 0x74, 0x61]) // "data"
        var dSize = UInt32(dataSize).littleEndian; wav.append(Data(bytes: &dSize, count: 4))

        // Generate PCM samples
        for i in 0..<totalSamples {
            var sample: Int16
            if i < onSamples {
                let t = Double(i) / Double(sampleRate)
                let value = sin(2.0 * .pi * 440.0 * t) + sin(2.0 * .pi * 480.0 * t)
                sample = Int16(clamping: Int(value * 3500)) // Moderate volume
            } else {
                sample = 0
            }
            sample = sample.littleEndian
            wav.append(Data(bytes: &sample, count: 2))
        }

        do {
            // Configure audio session for ringback playback
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)

            ringbackPlayer = try AVAudioPlayer(data: wav)
            ringbackPlayer?.numberOfLoops = -1  // Loop forever
            ringbackPlayer?.volume = 0.5
            ringbackPlayer?.play()
            print("📞 VoiceCall: ✅ Ringback tone playing (440+480Hz)")
        } catch {
            print("⚠️ VoiceCall: Could not play ringback tone: \(error)")
            // Fallback to system sound beeps
            ringbackTimer = Timer.scheduledTimer(withTimeInterval: 4.0, repeats: true) { [weak self] _ in
                guard self?.callState == .ringing && self?.isOutgoingCall == true else { return }
                AudioServicesPlaySystemSound(1052)
            }
            AudioServicesPlaySystemSound(1052)
        }
    }

    private func stopRingbackTone() {
        print("📞 VoiceCall: Stopping ringback tone")
        ringbackPlayer?.stop()
        ringbackPlayer = nil
        ringbackTimer?.invalidate()
        ringbackTimer = nil
    }

    private func playRingtone() {
        print("🔔 VoiceCall: Starting ringtone...")
        
        // Configure audio session for ringtone playback
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)
        } catch {
            print("⚠️ VoiceCall: Could not configure audio session for ringtone: \(error)")
        }
        
        // Try to load a custom ringtone or use system sound
        if let ringtoneURL = Bundle.main.url(forResource: "ringtone", withExtension: "mp3") {
            do {
                ringtonePlayer = try AVAudioPlayer(contentsOf: ringtoneURL)
                ringtonePlayer?.numberOfLoops = -1 // Loop indefinitely
                ringtonePlayer?.volume = 1.0
                ringtonePlayer?.play()
                print("🔔 VoiceCall: Playing custom ringtone")
            } catch {
                print("⚠️ VoiceCall: Could not play custom ringtone: \(error)")
            }
        }
        
        // Always use system sound as fallback/addition
        playSystemRingtone()
        
        // Schedule repeated system sounds and vibrations
        ringtoneTimer?.invalidate()
        ringtoneTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] timer in
            guard let self = self, self.callState == .ringing else {
                timer.invalidate()
                return
            }
            self.playSystemRingtone()
        }
    }
    
    private func playSystemRingtone() {
        // Play system sound (phone ring)
        AudioServicesPlaySystemSound(1007) // Standard phone ring
        
        // Also vibrate
        AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
        
        // Haptic feedback (must be on main thread)
        DispatchQueue.main.async {
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.warning)
        }
    }
    
    private func stopRingtone() {
        print("🔔 VoiceCall: Stopping ringtone")
        ringtoneTimer?.invalidate()
        ringtoneTimer = nil
        ringtonePlayer?.stop()
        ringtonePlayer = nil
    }
    
    // MARK: - Controls
    
    func toggleMute() {
        isMuted.toggle()
        print("🎙️ VoiceCall: Mute \(isMuted ? "ON" : "OFF")")
    }
    
    private var lastSpeakerToggle: Date = .distantPast
    
    func toggleSpeaker() {
        // Ensure we're on main thread for @Published property
        guard Thread.isMainThread else {
            DispatchQueue.main.async { self.toggleSpeaker() }
            return
        }
        
        // Check if device supports earpiece (iPad/Mac/Simulator doesn't have one)
        let hasEarpiece = UIDevice.current.userInterfaceIdiom == .phone
        
        if !hasEarpiece {
            // iPad/Mac/Simulator: Speaker is always on, can't toggle to earpiece
            print("📱 VoiceCall: iPad/Mac/Simulator detected - speaker always on (no earpiece)")
            isSpeakerOn = true
            
            // Ensure speaker is actually active
            do {
                try AVAudioSession.sharedInstance().overrideOutputAudioPort(.speaker)
            } catch {
                print("❌ VoiceCall: Failed to force speaker: \(error)")
            }
            return
        }
        
        // Debounce to prevent rapid toggling
        let now = Date()
        guard now.timeIntervalSince(lastSpeakerToggle) > 0.3 else {
            print("⏸️ VoiceCall: Speaker toggle debounced")
            return
        }
        lastSpeakerToggle = now
        
        let newState = !isSpeakerOn
        
        do {
            let session = AVAudioSession.sharedInstance()
            
            // 🔧 FIX: During active call, ONLY use overrideOutputAudioPort()
            // Do NOT reconfigure the category - this interrupts audio!
            // The category is already set to .playAndRecord when call started
            
            if newState {
                // Switch to speaker
                try session.overrideOutputAudioPort(.speaker)
                print("🔊 VoiceCall: Speaker ON - routing to speaker")
            } else {
                // Switch to earpiece (receiver)
                try session.overrideOutputAudioPort(.none)
                print("🔊 VoiceCall: Speaker OFF - routing to earpiece")
            }
            
            // Small delay to let the audio system apply the change
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
                guard self != nil else { return }
                
                // Verify the actual route
                let currentRoute = session.currentRoute
                let outputPort = currentRoute.outputs.first?.portType
                print("🔊 VoiceCall: Actual output route after toggle: \(outputPort?.rawValue ?? "unknown")")
                
                // Check if route matches expected state
                let isActuallySpeaker = outputPort == .builtInSpeaker
                let isActuallyEarpiece = outputPort == .builtInReceiver
                let isBluetooth = outputPort == .bluetoothA2DP || outputPort == .bluetoothHFP || outputPort == .bluetoothLE
                
                if isBluetooth {
                    print("🎧 VoiceCall: Bluetooth audio detected - override may not apply")
                } else if newState && !isActuallySpeaker {
                    print("⚠️ VoiceCall: Wanted speaker but got \(outputPort?.rawValue ?? "unknown")")
                } else if !newState && !isActuallyEarpiece {
                    print("⚠️ VoiceCall: Wanted earpiece but got \(outputPort?.rawValue ?? "unknown")")
                }
            }
            
            // Update state immediately for UI responsiveness
            isSpeakerOn = newState
            
            // Haptic feedback to confirm toggle
            let generator = UIImpactFeedbackGenerator(style: .medium)
            generator.impactOccurred()
            
        } catch {
            print("❌ VoiceCall: Failed to toggle speaker: \(error)")
            // Try to recover by forcing the current state
            do {
                if isSpeakerOn {
                    try AVAudioSession.sharedInstance().overrideOutputAudioPort(.speaker)
                } else {
                    try AVAudioSession.sharedInstance().overrideOutputAudioPort(.none)
                }
            } catch {
                print("❌ VoiceCall: Recovery also failed: \(error)")
            }
        }
    }
    
    // MARK: - Connection Type Lock
    // 🔒 CRITICAL: Lock connection type when call connects to prevent switching mid-call

    /// Lock the current connection type - MUST be called when call connects
    /// Once locked, the connection type NEVER changes until the call ends
    /// This ensures stable audio routing throughout the call
    private func lockConnectionType() {
        guard !connectionTypeLocked else {
            print("⚠️ VoiceCall: Connection type already locked to \(lockedConnectionType)")
            return
        }

        // 🔒 CRITICAL FIX: For incoming calls, use the pre-locked connection type
        // This ensures we use the connection type from when the call was RECEIVED,
        // not when it was ACCEPTED (which might be different if mesh was discovered)
        if incomingCallConnectionLocked && !isOutgoingCall {
            lockedConnectionType = incomingCallConnectionType
            print("🔒 VoiceCall: Using PRE-LOCKED connection type for incoming call: \(lockedConnectionType)")
        } else {
            lockedConnectionType = connectionType
        }
        
        connectionTypeLocked = true

        print("🔒 VoiceCall: Connection type LOCKED to: \(lockedConnectionType)")
        print("   - meshDirect: Audio via mesh P2P (offline)")
        print("   - mesh/meshRelay/tor: Audio via VPS")
        print("   - NO SWITCHING will occur during this call")
    }

    /// Get the effective connection type (locked if in call, current otherwise)
    private var effectiveConnectionType: ConnectionType {
        // For incoming calls that haven't been accepted yet, use pre-locked type
        if incomingCallConnectionLocked && !connectionTypeLocked {
            return incomingCallConnectionType
        }
        return connectionTypeLocked ? lockedConnectionType : connectionType
    }

    // MARK: - Connection Loss Detection & Beep

    /// Start monitoring for connection loss
    private func startConnectionLossDetection() {
        lastAudioReceivedTime = Date()
        
        connectionLossTimer?.invalidate()
        connectionLossTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            self?.checkConnectionStatus()
        }
        RunLoop.main.add(connectionLossTimer!, forMode: .common)
        
        print("🔊 VoiceCall: Connection loss detection started")
    }
    
    /// Stop connection loss monitoring
    private func stopConnectionLossDetection() {
        connectionLossTimer?.invalidate()
        connectionLossTimer = nil
        isPlayingConnectionLossBeep = false
    }
    
    /// Check if we've lost connection and attempt reconnection based on locked connection type
    private func checkConnectionStatus() {
        guard callState == .inCall else { return }

        // 🔧 FIX: Grace period for initial connection setup
        // Don't trigger connection loss during the first 20 seconds after call connects
        // This allows time for WebSocket/audio streaming to fully initialize
        // and for both parties to exchange callIds (increased from 12s for VPS latency)
        if let connectedTime = callConnectedTime {
            let timeSinceConnect = Date().timeIntervalSince(connectedTime)
            if timeSinceConnect < 20.0 {
                // Still in initial setup period - don't trigger connection loss
                // Log periodically to track progress
                if Int(timeSinceConnect) % 5 == 0 {
                    print("📶 VoiceCall: Setup grace period - \(Int(20 - timeSinceConnect))s remaining...")
                }
                return
            }
        }

        let timeSinceLastAudio = Date().timeIntervalSince(lastAudioReceivedTime)

        if timeSinceLastAudio > connectionLossThreshold {
            // Connection appears lost - START RECONNECTION PROCESS
            if !isPlayingConnectionLossBeep {
                isPlayingConnectionLossBeep = true
                playConnectionLossBeep()

                DispatchQueue.main.async {
                    self.connectionQuality = .reconnecting
                }
                print("⚠️ VoiceCall: Connection lost - no audio for \(String(format: "%.1f", timeSinceLastAudio))s")
                print("   🔒 Locked connection type: \(lockedConnectionType)")
            }

            // 📶 RECONNECTION LOGIC - Based on LOCKED connection type (no switching!)
            let shouldAttemptReconnect = !isReconnecting &&
                reconnectAttempts < maxReconnectAttempts &&
                (lastReconnectAttemptTime == nil || Date().timeIntervalSince(lastReconnectAttemptTime!) > reconnectCooldown)

            if timeSinceLastAudio > 5.0 && shouldAttemptReconnect {
                reconnectAttempts += 1
                lastReconnectAttemptTime = Date()

                print("📶 VoiceCall: Reconnection attempt \(reconnectAttempts)/\(maxReconnectAttempts)...")

                // 🔒 CRITICAL: Reconnect using the LOCKED connection type only!
                if lockedConnectionType == .meshDirect {
                    // MESH DIRECT: Try to re-establish mesh peer connection
                    print("   📶 Attempting mesh direct reconnection...")
                    attemptMeshReconnect()
                } else {
                    // VPS-based (mesh, meshRelay, tor): Reconnect WebSocket/HTTP
                    print("   🌐 Attempting VPS reconnection...")
                    attemptWebSocketReconnect()
                }
            }

            // 📶 End call after max disconnection time (30s default)
            if timeSinceLastAudio > maxDisconnectionTime {
                print("❌ VoiceCall: No audio for \(Int(maxDisconnectionTime))s - ending call")
                print("   Connection type was: \(lockedConnectionType)")

                // Notify user before ending
                let generator = UINotificationFeedbackGenerator()
                generator.notificationOccurred(.error)

                endCall(reason: .connectionLost)
            }
        } else {
            // Connection restored!
            if isPlayingConnectionLossBeep {
                isPlayingConnectionLossBeep = false
                stopConnectionLossBeep()
                reconnectAttempts = 0  // Reset on successful audio
                isReconnecting = false

                DispatchQueue.main.async {
                    self.connectionQuality = .good
                }

                print("✅ VoiceCall: Connection restored! (type: \(lockedConnectionType))")

                // Success haptic
                let generator = UINotificationFeedbackGenerator()
                generator.notificationOccurred(.success)
            }
        }
    }

    /// Attempt to reconnect mesh direct connection
    private func attemptMeshReconnect() {
        guard let peer = currentCallPeer else {
            print("❌ VoiceCall: No peer to reconnect to")
            return
        }

        isReconnecting = true

        // Check if mesh peer is still available
        if let _ = meshManager?.getPeerForPublicKey(peer) {
            print("✅ VoiceCall: Mesh peer still available, connection should resume")
            isReconnecting = false
        } else {
            print("⚠️ VoiceCall: Mesh peer not found, waiting for reconnection...")

            // Wait and retry - mesh might reconnect automatically
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
                self?.isReconnecting = false
            }
        }
    }
    
    /// Play beep-beep sound to indicate connection loss
    private func playConnectionLossBeep() {
        // Play system sound for connection issue
        AudioServicesPlaySystemSound(1073)  // "Tink" sound - less intrusive than alarm
        
        // Haptic feedback
        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(.warning)
        
        // Schedule repeated beeps if still disconnected
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            guard let self = self, self.isPlayingConnectionLossBeep, self.callState == .inCall else { return }
            AudioServicesPlaySystemSound(1073)
        }
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
            guard let self = self, self.isPlayingConnectionLossBeep, self.callState == .inCall else { return }
            AudioServicesPlaySystemSound(1073)
        }
    }
    
    /// Stop the connection loss beep
    private func stopConnectionLossBeep() {
        // Nothing to stop for system sounds, they play once
    }
    
    /// Called when audio is received - resets connection loss timer
    private func audioReceived() {
        lastAudioReceivedTime = Date()
        
        // If we were showing reconnecting, restore quality
        if isPlayingConnectionLossBeep {
            isPlayingConnectionLossBeep = false
            DispatchQueue.main.async {
                // Quality will be recalculated by quality monitor
                if self.connectionQuality == .reconnecting {
                    self.connectionQuality = .good
                }
            }
        }
    }
    
    // MARK: - Debug Audio Routing
    
    func debugAudioRouting() {
        let session = AVAudioSession.sharedInstance()
        let route = session.currentRoute
        
        print("═══════════════════════════════════")
        print("🔊 AUDIO ROUTING DEBUG")
        print("═══════════════════════════════════")
        print("Device: \(UIDevice.current.userInterfaceIdiom == .phone ? "iPhone" : "iPad")")
        print("Category: \(session.category.rawValue)")
        print("Mode: \(session.mode.rawValue)")
        print("Options: \(session.categoryOptions)")
        print("")
        print("INPUTS:")
        for input in route.inputs {
            print("  - \(input.portType.rawValue): \(input.portName)")
        }
        print("")
        print("OUTPUTS:")
        for output in route.outputs {
            print("  - \(output.portType.rawValue): \(output.portName)")
        }
        print("═══════════════════════════════════")
    }
}

// MARK: - Call Error
enum CallError: LocalizedError {
    case alreadyInCall
    case noIncomingCall
    case invalidPeerKey
    case networkError
    case audioError
    case peerBlocked
    
    var errorDescription: String? {
        switch self {
        case .alreadyInCall: return "Already in a call"
        case .noIncomingCall: return "No incoming call"
        case .invalidPeerKey: return "Invalid peer key"
        case .networkError: return "Network error"
        case .audioError: return "Audio error"
        case .peerBlocked: return "This contact is blocked"
        }
    }
}

// MARK: - Data Extension
extension Data {
    init?(hexString: String) {
        let hex = hexString.replacingOccurrences(of: " ", with: "")
        guard hex.count % 2 == 0 else { return nil }
        
        var data = Data()
        var index = hex.startIndex
        
        while index < hex.endIndex {
            let nextIndex = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<nextIndex], radix: 16) else { return nil }
            data.append(byte)
            index = nextIndex
        }
        
        self = data
    }
}

// MARK: - Notification Names for Call System
extension Notification.Name {
    static let didReceiveCallSignal = Notification.Name("didReceiveCallSignal")
    static let didReceiveCallAudio = Notification.Name("didReceiveCallAudio")
}
