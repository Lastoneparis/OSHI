//
//  MeshDirectCallManager.swift
//  OSHI - Direct Mesh Voice/Video Calls
//
//  📞 Enables peer-to-peer calls over mesh network when directly connected
//  No VPS needed - works completely offline!
//
//  Flow:
//  1. Check if peer is directly connected via mesh
//  2. If YES → Use mesh direct call (P2P audio streaming)
//  3. If NO → Fall back to VPS-based signaling
//
//  ✅ FIXED: Proper audio codec (G.726 ADPCM - same as VoiceCallManager)
//  ✅ FIXED: Jitter buffer for smooth playback
//  ✅ FIXED: Thread-safe audio buffer operations
//  ✅ FIXED: Proper coordination with VoiceCallManager
//

import Foundation
import AVFoundation
import Combine
import UserNotifications

// MARK: - Mesh Audio Jitter Buffer
// Handles packet reordering and timing for smooth audio playback

class MeshAudioJitterBuffer {
    struct BufferedPacket {
        let sequenceNumber: UInt64
        let timestamp: Date
        let audioData: Data
    }

    private var buffer: [UInt64: BufferedPacket] = [:]
    private var nextExpectedSeq: UInt64 = 0
    private let minBufferMs: Double = 60   // 3 packets at 20ms each
    private let maxBufferMs: Double = 200  // 10 packets
    private let packetDurationMs: Double = 20
    private let lock = NSLock()

    // Statistics
    private(set) var packetsReceived: UInt64 = 0
    private(set) var packetsLate: UInt64 = 0
    private(set) var packetsReordered: UInt64 = 0

    func addPacket(sequenceNumber: UInt64, audioData: Data) -> Bool {
        lock.lock()
        defer { lock.unlock() }

        packetsReceived += 1

        if nextExpectedSeq == 0 {
            nextExpectedSeq = sequenceNumber
        }

        // Reject duplicates
        if buffer[sequenceNumber] != nil {
            return false
        }

        // Check if packet is too late
        if sequenceNumber < nextExpectedSeq {
            packetsLate += 1
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

        // Limit buffer size
        while buffer.count > 15 {
            if let minSeq = buffer.keys.min() {
                buffer.removeValue(forKey: minSeq)
            }
        }

        return true
    }

    func getNextPacket() -> Data? {
        lock.lock()
        defer { lock.unlock() }

        if let packet = buffer[nextExpectedSeq] {
            buffer.removeValue(forKey: nextExpectedSeq)
            nextExpectedSeq += 1
            return packet.audioData
        }

        // Packet missing - skip ahead if gap is too large
        let bufferedSeqs = buffer.keys.sorted()
        if let firstAvailable = bufferedSeqs.first {
            let gap = firstAvailable - nextExpectedSeq
            if gap > 3 {  // Skip if more than 3 packets behind
                nextExpectedSeq = firstAvailable
                if let packet = buffer[firstAvailable] {
                    buffer.removeValue(forKey: firstAvailable)
                    nextExpectedSeq += 1
                    return packet.audioData
                }
            }
        }

        return nil
    }

    var bufferDepthPackets: Int {
        lock.lock()
        defer { lock.unlock() }
        return buffer.count
    }

    var isReady: Bool {
        return bufferDepthPackets >= 3  // Wait for 3 packets before starting playback
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

// MARK: - Mesh Audio Codec (G.726 ADPCM - Same as VoiceCallManager)
// Provides efficient audio compression without external dependencies

class MeshAudioCodec {
    private var encodeState = MeshG726State()
    private var decodeState = MeshG726State()
    private var consecutiveSilentFrames: Int = 0
    private let silenceThreshold: Int16 = 500
    private let maxSilentFrames: Int = 5

    func encode(_ pcmData: Data) -> Data {
        // Convert Float32 PCM to Int16 samples
        var samples = [Int16]()
        let floatCount = pcmData.count / MemoryLayout<Float>.size
        samples.reserveCapacity(floatCount)

        pcmData.withUnsafeBytes { buffer in
            let floats = buffer.bindMemory(to: Float.self)
            for i in 0..<floatCount {
                // Convert float [-1, 1] to Int16
                let clamped = max(-1.0, min(1.0, floats[i]))
                samples.append(Int16(clamped * 32767))
            }
        }

        // Downsample to 8kHz (6:1 from 48kHz)
        var downsampled = [Int16]()
        downsampled.reserveCapacity(samples.count / 6)
        for i in stride(from: 0, to: samples.count - 5, by: 6) {
            var sum: Int32 = 0
            for j in 0..<6 {
                sum += Int32(samples[i + j])
            }
            downsampled.append(Int16(clamping: sum / 6))
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

        // G.726 ADPCM encoding (4 bits per sample)
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
            // Return comfort noise
            var data = Data()
            for _ in 0..<1024 {  // ~20ms at 48kHz
                var noise = Float.random(in: -0.001...0.001)
                data.append(Data(bytes: &noise, count: MemoryLayout<Float>.size))
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

        // Upsample from 8kHz to 48kHz (6:1 interpolation)
        var samples48k = [Float]()
        samples48k.reserveCapacity(samples8k.count * 6)

        for i in 0..<samples8k.count {
            let current = Float(samples8k[i]) / 32767.0
            let next = i < samples8k.count - 1 ? Float(samples8k[i + 1]) / 32767.0 : current

            for j in 0..<6 {
                let t = Float(j) / 6.0
                let interpolated = current + (next - current) * t
                samples48k.append(interpolated)
            }
        }

        // Convert to Data
        var decoded = Data()
        decoded.reserveCapacity(samples48k.count * MemoryLayout<Float>.size)
        for sample in samples48k {
            var s = sample
            decoded.append(Data(bytes: &s, count: MemoryLayout<Float>.size))
        }

        return decoded
    }

    func reset() {
        encodeState.reset()
        decodeState.reset()
        consecutiveSilentFrames = 0
    }
}

// G.726 State Machine
private class MeshG726State {
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

// MARK: - Mesh Call State

enum MeshCallState: String {
    case idle = "idle"
    case ringing = "ringing"           // Outgoing call, waiting for answer
    case incomingCall = "incoming"     // Incoming call, waiting for user action
    case connecting = "connecting"     // Setting up audio stream
    case active = "active"             // Call in progress
    case ended = "ended"
    case failed = "failed"
}

// MARK: - Mesh Call Signal Types

enum MeshCallSignalType: String, Codable {
    case offer = "mesh_call_offer"
    case answer = "mesh_call_answer"
    case reject = "mesh_call_reject"
    case hangup = "mesh_call_hangup"
    case audioData = "mesh_audio_data"
    case videoData = "mesh_video_data"  // NEW: Video support
    case keepAlive = "mesh_call_alive"
}

// MARK: - Mesh Call Signal

struct MeshCallSignal: Codable {
    let type: MeshCallSignalType
    let callId: String
    let senderKey: String
    let recipientKey: String
    let timestamp: Date
    let payload: Data?  // Audio/video data for streaming
    let sequenceNumber: UInt64?  // For jitter buffer ordering
    let isVideoCall: Bool?  // NEW: Indicates if this is a video call

    init(type: MeshCallSignalType, callId: String, senderKey: String, recipientKey: String, payload: Data? = nil, sequenceNumber: UInt64? = nil, isVideoCall: Bool? = nil) {
        self.type = type
        self.callId = callId
        self.senderKey = senderKey
        self.recipientKey = recipientKey
        self.timestamp = Date()
        self.payload = payload
        self.sequenceNumber = sequenceNumber
        self.isVideoCall = isVideoCall
    }
}

// MARK: - Mesh Direct Call Manager

class MeshDirectCallManager: NSObject, ObservableObject {
    static let shared = MeshDirectCallManager()

    // MARK: - Published State
    @Published var callState: MeshCallState = .idle
    @Published var currentCallId: String?
    @Published var remotePeerKey: String?
    @Published var callDuration: TimeInterval = 0
    @Published var isMuted: Bool = false
    @Published var isSpeakerOn: Bool = false
    @Published var isUsingMeshDirect: Bool = false  // true = mesh, false = VPS
    @Published var isVideoCall: Bool = false  // NEW: Video call support
    @Published var audioQuality: String = "Good"  // Audio quality indicator

    // MARK: - Audio Engine
    private var audioEngine: AVAudioEngine?
    private var audioPlayerNode: AVAudioPlayerNode?
    private var audioFormat: AVAudioFormat?
    private let audioBufferLock = NSLock()  // Thread-safe buffer access

    // MARK: - NEW: Audio Processing Components
    private let audioCodec = MeshAudioCodec()
    private let jitterBuffer = MeshAudioJitterBuffer()
    private var audioSequenceNumber: UInt64 = 0
    private var playbackTimer: Timer?
    private var isPlayingAudio: Bool = false

    // MARK: - Timers
    private var callTimer: Timer?
    private var keepAliveTimer: Timer?
    private var ringTimeout: Timer?

    // MARK: - Mesh Network Reference
    private weak var meshManager: MeshNetworkManager?

    // MARK: - Constants
    private let ringTimeoutSeconds: TimeInterval = 30
    private let keepAliveInterval: TimeInterval = 5
    private let audioPlaybackIntervalMs: Double = 20  // 20ms audio packets
    
    private override init() {
        super.init()
        setupObservers()
        
        OshiLog.call.info("")
        OshiLog.call.info("╔════════════════════════════════════════════════════════╗")
        OshiLog.call.info("║  📞 MeshDirectCallManager INITIALIZED                 ║")
        OshiLog.call.info("║  🔗 P2P calls over mesh network enabled               ║")
        OshiLog.call.info("╚════════════════════════════════════════════════════════╝")
        OshiLog.call.info("")
    }
    
    // MARK: - Setup
    
    func configure(meshManager: MeshNetworkManager) {
        self.meshManager = meshManager
    }
    
    private func setupObservers() {
        // 🔴 WATCHDOG (0x8BADF00D): these notifications are posted from background
        // threads. `queue: .main` would park the POSTER on `-[NSOperation waitUntilFinished]`
        // until the main runloop drained — deadlocking whenever main was itself waiting on
        // that thread's queue/lock. addMainThreadObserver hops to main without blocking it.
        // See NotificationCenter+MainThread in NotificationManager.swift.
        // Listen for incoming mesh call signals
        NotificationCenter.default.addMainThreadObserver(
            forName: NSNotification.Name("MeshCallSignalReceived")
        ) { [weak self] notification in
            guard let signalData = notification.userInfo?["signalData"] as? Data else { return }
            self?.handleIncomingSignal(signalData)
        }
        
        // Listen for mesh peer disconnection
        NotificationCenter.default.addMainThreadObserver(
            forName: NSNotification.Name("MeshPeerDisconnected")
        ) { [weak self] notification in
            guard let peerKey = notification.userInfo?["peerKey"] as? String else { return }
            self?.handlePeerDisconnected(peerKey)
        }
    }
    
    // MARK: - Check Direct Connection
    
    /// Check if we can make a direct mesh call to this peer
    func canMakeDirectCall(to peerKey: String) -> Bool {
        guard let mesh = meshManager else { return false }
        
        // Check if peer is directly connected (1 hop)
        return mesh.isPeerDirectlyConnected(peerKey)
    }
    
    /// Get best call method for peer
    func getBestCallMethod(for peerKey: String) -> CallMethod {
        if canMakeDirectCall(to: peerKey) {
            return .meshDirect
        } else if isNetworkAvailable() {
            return .vps
        } else {
            return .unavailable
        }
    }
    
    enum CallMethod {
        case meshDirect
        case vps
        case unavailable
    }
    
    private func isNetworkAvailable() -> Bool {
        // Simple network check
        guard let url = URL(string: "https://www.apple.com") else { return false }
        var request = URLRequest(url: url)
        request.timeoutInterval = 2
        request.httpMethod = "HEAD"
        
        let semaphore = DispatchSemaphore(value: 0)
        var isAvailable = false
        
        URLSession.shared.dataTask(with: request) { _, response, _ in
            isAvailable = (response as? HTTPURLResponse)?.statusCode == 200
            semaphore.signal()
        }.resume()
        
        _ = semaphore.wait(timeout: .now() + 2)
        return isAvailable
    }
    
    // MARK: - Start Call
    
    /// Start a mesh direct call (voice only - video uses VPS)
    /// - Parameters:
    ///   - peerKey: The recipient's public key
    ///   - identityManager: Identity manager for signing
    ///   - isVideo: If true, will fallback to VPS (mesh can't handle video bandwidth)
    func startCall(to peerKey: String, identityManager: IdentityManager, isVideo: Bool = false) {
        guard callState == .idle else {
            OshiLog.call.info("❌ Cannot start call - already in call state: \(callState)")
            return
        }

        // 🔧 VIDEO CALLS: Always use VPS - mesh can't handle video bandwidth
        if isVideo {
            OshiLog.call.info("📹 MeshDirectCall: Video calls require VPS relay (mesh bandwidth insufficient)")
            NotificationCenter.default.post(
                name: NSNotification.Name("UseFallbackVPSCall"),
                object: nil,
                userInfo: [
                    "peerKey": peerKey,
                    "reason": "video_requires_vps",
                    "isVideoCall": true
                ]
            )
            return
        }

        // 🔧 FIX: Check if VoiceCallManager is already handling a call
        let voiceCallManager = VoiceCallManager.shared
        if voiceCallManager.callState != .idle {
            OshiLog.call.info("⚠️ MeshDirectCall: VoiceCallManager is busy - deferring to it")
            return
        }

        // 🔧 FIX: Check mesh reachability with detailed logging
        let canDirect = canMakeDirectCall(to: peerKey)
        OshiLog.call.info("📡 MeshDirectCall: Checking peer reachability...")
        OshiLog.call.info("   Peer: \(peerKey.prefix(16))...")
        OshiLog.call.info("   Direct connection available: \(canDirect)")

        guard canDirect else {
            OshiLog.call.info("⚠️ Peer not directly connected - requesting VPS fallback")

            // 🔧 FIX: Include more context in fallback notification
            NotificationCenter.default.post(
                name: NSNotification.Name("UseFallbackVPSCall"),
                object: nil,
                userInfo: [
                    "peerKey": peerKey,
                    "reason": "not_in_mesh",
                    "meshPeersCount": meshManager?.connectedPeers.count ?? 0
                ]
            )
            return
        }
        
        let callId = UUID().uuidString
        currentCallId = callId
        remotePeerKey = peerKey
        isUsingMeshDirect = true
        
        OshiLog.call.info("📞 Starting MESH DIRECT call to: \(peerKey.prefix(12))...")
        OshiLog.call.info("   Call ID: \(callId.prefix(8))...")
        
        // Create offer signal
        let signal = MeshCallSignal(
            type: .offer,
            callId: callId,
            senderKey: identityManager.publicKey,
            recipientKey: peerKey
        )
        
        // Send via mesh
        sendSignalViaMesh(signal, to: peerKey)
        
        // Update state
        callState = .ringing
        
        // Start ring timeout
        ringTimeout = Timer.scheduledTimer(withTimeInterval: ringTimeoutSeconds, repeats: false) { [weak self] _ in
            self?.handleRingTimeout()
        }
        
        // Play outgoing ring tone
        playRingTone(outgoing: true)
        
        // Notify UI
        NotificationCenter.default.post(
            name: NSNotification.Name("MeshCallStarted"),
            object: nil,
            userInfo: [
                "callId": callId,
                "peerKey": peerKey,
                "isMeshDirect": true
            ]
        )
    }
    
    // MARK: - Answer Call
    
    func answerCall() {
        guard callState == .incomingCall,
              let callId = currentCallId,
              let peerKey = remotePeerKey,
              let identityManager = MessageManager.sharedIdentityManager else {
            return
        }

        // 🔧 FIX: Check if VoiceCallManager is already handling this call
        // This prevents duplicate answer handling and call state conflicts
        let voiceCallManager = VoiceCallManager.shared
        if voiceCallManager.callState == .inCall {
            OshiLog.call.info("⚠️ MeshDirectCall: VoiceCallManager already in call - deferring to it")
            return
        }

        OshiLog.call.info("📞 Answering mesh direct call...")

        // Send answer signal
        let signal = MeshCallSignal(
            type: .answer,
            callId: callId,
            senderKey: identityManager.publicKey,
            recipientKey: peerKey
        )

        sendSignalViaMesh(signal, to: peerKey)

        // Setup audio
        setupAudioSession()
        startAudioEngine()

        // Update state
        callState = .active
        callDuration = 0

        // Start call timer
        callTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.callDuration += 1
        }

        // Start keep-alive
        startKeepAlive()

        // Stop ring tone
        stopRingTone()

        OshiLog.call.info("✅ MeshDirectCall: Call answered and active")
    }
    
    // MARK: - Reject Call
    
    func rejectCall() {
        guard callState == .incomingCall,
              let callId = currentCallId,
              let peerKey = remotePeerKey,
              let identityManager = MessageManager.sharedIdentityManager else {
            return
        }
        
        OshiLog.call.info("📞 Rejecting mesh direct call")
        
        // Send reject signal
        let signal = MeshCallSignal(
            type: .reject,
            callId: callId,
            senderKey: identityManager.publicKey,
            recipientKey: peerKey
        )
        
        sendSignalViaMesh(signal, to: peerKey)
        
        // Cleanup
        endCallCleanup()
    }
    
    // MARK: - Hang Up
    
    func hangUp() {
        guard let callId = currentCallId,
              let peerKey = remotePeerKey,
              let identityManager = MessageManager.sharedIdentityManager else {
            endCallCleanup()
            return
        }
        
        OshiLog.call.info("📞 Hanging up mesh direct call")
        
        // Send hangup signal
        let signal = MeshCallSignal(
            type: .hangup,
            callId: callId,
            senderKey: identityManager.publicKey,
            recipientKey: peerKey
        )
        
        sendSignalViaMesh(signal, to: peerKey)
        
        // Cleanup
        endCallCleanup()
    }
    
    // MARK: - Handle Incoming Signals
    
    private func handleIncomingSignal(_ data: Data) {
        guard let signal = try? JSONDecoder().decode(MeshCallSignal.self, from: data) else {
            OshiLog.call.info("❌ Failed to decode mesh call signal")
            return
        }
        
        OshiLog.call.info("📞 Received mesh call signal: \(signal.type.rawValue)")
        
        switch signal.type {
        case .offer:
            handleIncomingOffer(signal)

        case .answer:
            handleCallAnswered(signal)

        case .reject:
            handleCallRejected(signal)

        case .hangup:
            handleRemoteHangup(signal)

        case .audioData:
            handleAudioData(signal)

        case .videoData:
            handleVideoData(signal)

        case .keepAlive:
            // Just acknowledge - connection is alive
            resetKeepAliveTimeout()
        }
    }

    private func handleVideoData(_ signal: MeshCallSignal) {
        guard signal.callId == currentCallId,
              callState == .active,
              isVideoCall,
              let videoData = signal.payload else { return }

        // Forward video data to VideoCallManager for decoding
        // Mesh video calls are not fully supported - recommend VPS for video
        NotificationCenter.default.post(
            name: NSNotification.Name("MeshVideoDataReceived"),
            object: nil,
            userInfo: [
                "videoData": videoData,
                "senderKey": signal.senderKey
            ]
        )
    }

    private var keepAliveTimeoutTimer: Timer?

    private func resetKeepAliveTimeout() {
        keepAliveTimeoutTimer?.invalidate()
        keepAliveTimeoutTimer = Timer.scheduledTimer(withTimeInterval: 15.0, repeats: false) { [weak self] _ in
            OshiLog.call.info("⚠️ Mesh call: No keep-alive received for 15 seconds")
            self?.handleConnectionLost()
        }
    }

    private func handleConnectionLost() {
        guard callState == .active else { return }

        OshiLog.call.info("📞 Mesh call: Connection lost")
        callState = .failed

        NotificationCenter.default.post(
            name: NSNotification.Name("MeshCallFailed"),
            object: nil,
            userInfo: ["reason": "Connection lost"]
        )

        endCallCleanup()
    }
    
    private func handleIncomingOffer(_ signal: MeshCallSignal) {
        guard callState == .idle else {
            // Already in a call - send busy signal
            if let identityManager = MessageManager.sharedIdentityManager {
                let rejectSignal = MeshCallSignal(
                    type: .reject,
                    callId: signal.callId,
                    senderKey: identityManager.publicKey,
                    recipientKey: signal.senderKey
                )
                sendSignalViaMesh(rejectSignal, to: signal.senderKey)
            }
            return
        }

        // 🚫 BLOCKED CALLER. This path had no block check at all, so a blocked
        // contact calling over the mesh set `callState = .incomingCall` and rang
        // the phone — the one call route that bypasses both the VoIP push gate
        // (VoIPPushManager) and the relay gate (VoiceCallManager), and the one
        // that matters most offline, where mesh IS the transport.
        //
        // Dropped silently, like VoiceCallManager's relay path: no reject signal,
        // so a blocked caller learns nothing about whether the device is present,
        // reachable, or simply ignoring them.
        if BlockedContactsManager.shared.isBlocked(signal.senderKey) {
            OshiLog.call.info("🚫 MeshDirectCall: incoming offer from blocked contact — ignoring")
            return
        }

        // 🔧 CRITICAL FIX: Check if VoiceCallManager is already handling this call
        // This prevents duplicate notifications and conflicting call states
        let voiceCallManager = VoiceCallManager.shared
        if voiceCallManager.callState != .idle {
            OshiLog.call.info("⚠️ MeshDirectCall: VoiceCallManager is already handling a call - deferring to it")
            return
        }
        
        // 🔧 FIX: Check if CallKit/VoIP push is already handling an incoming call
        // This prevents mesh from showing duplicate notification when VoIP push is active
        if voiceCallManager.isCallKitHandlingIncoming {
            OshiLog.call.info("⚠️ MeshDirectCall: CallKit is already showing incoming call UI - skipping mesh notification")
            return
        }

        OshiLog.call.info("📞 Incoming MESH DIRECT call from: \(signal.senderKey.prefix(12))...")

        currentCallId = signal.callId
        remotePeerKey = signal.senderKey
        isUsingMeshDirect = true
        callState = .incomingCall

        // Play incoming ring
        playRingTone(outgoing: false)

        // 🔧 FIX: Don't show duplicate notification - VoiceCallManager will handle UI
        // showIncomingCallNotification(from: signal.senderKey)  // DISABLED to prevent duplicate

        // Notify UI - but let VoiceCallManager be the source of truth for call UI
        NotificationCenter.default.post(
            name: NSNotification.Name("MeshIncomingCall"),
            object: nil,
            userInfo: [
                "callId": signal.callId,
                "callerKey": signal.senderKey,
                "isMeshDirect": true
            ]
        )
    }
    
    private func handleCallAnswered(_ signal: MeshCallSignal) {
        guard signal.callId == currentCallId else { return }

        // 🔧 FIX: Check if VoiceCallManager is already handling this call (e.g., via VPS)
        // This prevents duplicate handling when both mesh and VPS signals arrive
        let voiceCallManager = VoiceCallManager.shared
        if voiceCallManager.callState == .inCall {
            OshiLog.call.info("⚠️ MeshDirectCall: VoiceCallManager already in call - skipping duplicate answer")
            return
        }

        OshiLog.call.info("📞 MeshDirectCall: Call answered!")

        ringTimeout?.invalidate()
        stopRingTone()

        // Setup audio
        setupAudioSession()
        startAudioEngine()

        // Update state
        callState = .active
        callDuration = 0

        // Start timers
        callTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.callDuration += 1
        }

        startKeepAlive()

        OshiLog.call.info("✅ MeshDirectCall: Caller side - call now active")
    }
    
    private func handleCallRejected(_ signal: MeshCallSignal) {
        guard signal.callId == currentCallId else { return }
        
        OshiLog.call.info("📞 Call rejected by peer")
        
        callState = .ended
        
        // Notify UI
        NotificationCenter.default.post(
            name: NSNotification.Name("MeshCallRejected"),
            object: nil,
            userInfo: ["callId": signal.callId]
        )
        
        endCallCleanup()
    }
    
    private func handleRemoteHangup(_ signal: MeshCallSignal) {
        guard signal.callId == currentCallId else { return }
        
        OshiLog.call.info("📞 Remote peer hung up")
        
        callState = .ended
        
        // Notify UI
        NotificationCenter.default.post(
            name: NSNotification.Name("MeshCallEnded"),
            object: nil,
            userInfo: ["callId": signal.callId, "duration": callDuration]
        )
        
        endCallCleanup()
    }
    
    // MARK: - Audio Streaming

    private func handleAudioData(_ signal: MeshCallSignal) {
        guard signal.callId == currentCallId,
              callState == .active,
              let audioData = signal.payload else { return }

        // Add to jitter buffer with sequence number
        let seq = signal.sequenceNumber ?? 0
        _ = jitterBuffer.addPacket(sequenceNumber: seq, audioData: audioData)

        // Update quality indicator based on buffer stats
        updateAudioQuality()

        // Start playback if not already running and buffer is ready
        if !isPlayingAudio && jitterBuffer.isReady {
            startAudioPlayback()
        }
    }

    private func updateAudioQuality() {
        let depth = jitterBuffer.bufferDepthPackets
        DispatchQueue.main.async { [weak self] in
            if depth >= 5 {
                self?.audioQuality = "Excellent"
            } else if depth >= 3 {
                self?.audioQuality = "Good"
            } else if depth >= 1 {
                self?.audioQuality = "Fair"
            } else {
                self?.audioQuality = "Poor"
            }
        }
    }

    private func setupAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetoothA2DP, .allowAirPlay])
            try session.setPreferredSampleRate(48000)
            try session.setPreferredIOBufferDuration(0.02)  // 20ms buffer
            try session.setActive(true)
            OshiLog.call.info("✅ Audio session configured for mesh call (48kHz, 20ms buffer)")
        } catch {
            OshiLog.call.info("❌ Audio session error: \(error)")
        }
    }

    private func startAudioEngine() {
        audioEngine = AVAudioEngine()
        guard let engine = audioEngine else { return }

        let inputNode = engine.inputNode
        let format = inputNode.outputFormat(forBus: 0)
        audioFormat = format

        OshiLog.call.info("📡 Mesh Audio Format: \(format.sampleRate)Hz, \(format.channelCount) channels")

        // Capture microphone input with proper buffer size for ~20ms
        let bufferSize = AVAudioFrameCount(format.sampleRate * 0.02)  // 20ms
        inputNode.installTap(onBus: 0, bufferSize: bufferSize, format: format) { [weak self] buffer, _ in
            self?.processAndSendAudio(buffer)
        }

        // Setup player for remote audio playback
        audioPlayerNode = AVAudioPlayerNode()
        if let player = audioPlayerNode {
            engine.attach(player)
            engine.connect(player, to: engine.mainMixerNode, format: format)
        }

        do {
            try engine.start()
            audioPlayerNode?.play()
            audioSequenceNumber = 0
            OshiLog.call.info("✅ Audio engine started with G.726 codec")
        } catch {
            OshiLog.call.info("❌ Audio engine error: \(error)")
            callState = .failed
            NotificationCenter.default.post(
                name: NSNotification.Name("MeshCallFailed"),
                object: nil,
                userInfo: ["reason": "Audio engine failed to start"]
            )
        }
    }

    private func startAudioPlayback() {
        guard !isPlayingAudio else { return }
        isPlayingAudio = true

        // Use a high-priority timer for smooth playback
        playbackTimer = Timer.scheduledTimer(withTimeInterval: audioPlaybackIntervalMs / 1000.0, repeats: true) { [weak self] _ in
            self?.playNextAudioPacket()
        }
        RunLoop.current.add(playbackTimer!, forMode: .common)
    }

    private func stopAudioPlayback() {
        isPlayingAudio = false
        playbackTimer?.invalidate()
        playbackTimer = nil
    }

    private func playNextAudioPacket() {
        guard let engine = audioEngine,
              let player = audioPlayerNode,
              let format = audioFormat,
              engine.isRunning else {
            return
        }

        // Get next packet from jitter buffer
        guard let encodedData = jitterBuffer.getNextPacket() else {
            // Buffer underrun - generate comfort noise
            if let buffer = createComfortNoiseBuffer(format: format) {
                player.scheduleBuffer(buffer)
            }
            return
        }

        // Decode audio using G.726 codec
        let decodedData = audioCodec.decode(encodedData)

        // Create audio buffer from decoded data
        let frameCount = decodedData.count / MemoryLayout<Float>.size
        guard frameCount > 0,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frameCount)) else {
            return
        }

        buffer.frameLength = AVAudioFrameCount(frameCount)
        decodedData.withUnsafeBytes { ptr in
            if let baseAddress = ptr.baseAddress, let floatData = buffer.floatChannelData?[0] {
                memcpy(floatData, baseAddress, decodedData.count)
            }
        }

        player.scheduleBuffer(buffer)
    }

    private func createComfortNoiseBuffer(format: AVAudioFormat) -> AVAudioPCMBuffer? {
        let frameCount = Int(format.sampleRate * 0.02)  // 20ms
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frameCount)) else {
            return nil
        }

        buffer.frameLength = AVAudioFrameCount(frameCount)
        if let floatData = buffer.floatChannelData?[0] {
            for i in 0..<frameCount {
                floatData[i] = Float.random(in: -0.001...0.001)  // Very quiet noise
            }
        }

        return buffer
    }

    private func processAndSendAudio(_ buffer: AVAudioPCMBuffer) {
        guard !isMuted,
              callState == .active,
              let callId = currentCallId,
              let peerKey = remotePeerKey,
              let identityManager = MessageManager.sharedIdentityManager else { return }

        // Convert buffer to data
        guard let channelData = buffer.floatChannelData else { return }
        let frameCount = Int(buffer.frameLength)
        let data = Data(bytes: channelData[0], count: frameCount * MemoryLayout<Float>.size)

        // Encode audio using G.726 codec (compresses ~24:1)
        let compressedData = audioCodec.encode(data)

        // Increment sequence number for jitter buffer
        audioSequenceNumber += 1

        // Send via mesh with sequence number
        let signal = MeshCallSignal(
            type: .audioData,
            callId: callId,
            senderKey: identityManager.publicKey,
            recipientKey: peerKey,
            payload: compressedData,
            sequenceNumber: audioSequenceNumber
        )

        sendSignalViaMesh(signal, to: peerKey)
    }
    
    // MARK: - Send Signal via Mesh
    
    private func sendSignalViaMesh(_ signal: MeshCallSignal, to peerKey: String) {
        guard let signalData = try? JSONEncoder().encode(signal) else {
            OshiLog.call.info("❌ Failed to encode call signal")
            return
        }

        // For audio data, use optimized audio path
        if signal.type == .audioData {
            // Send audio via the optimized mesh audio channel
            meshManager?.sendCallAudio(to: peerKey, data: signalData)
            return
        }

        // For control signals (offer, answer, hangup, etc.), use reliable messaging
        let meshMessage: [String: Any] = [
            "type": "mesh_call_signal",
            "data": signalData.base64EncodedString(),
            "timestamp": Date().timeIntervalSince1970
        ]

        guard let messageData = try? JSONSerialization.data(withJSONObject: meshMessage) else { return }

        // Send via mesh network with reliable delivery for control signals
        meshManager?.sendData(messageData, to: peerKey)

        OshiLog.call.info("📤 Sent call signal: \(signal.type.rawValue)")
    }
    
    // MARK: - Notifications (Offline capable!)
    
    private func showIncomingCallNotification(from callerKey: String) {
        // Get caller name from contacts
        let callerName = getContactName(for: callerKey) ?? String(callerKey.prefix(12)) + "..."
        
        // Create local notification (works without network!)
        let content = UNMutableNotificationContent()
        content.title = "📞 Incoming Mesh Call"
        content.body = "\(callerName) is calling via mesh network"
        content.sound = UNNotificationSound(named: UNNotificationSoundName("ringtone.wav"))
        content.categoryIdentifier = "MESH_CALL"
        
        // __CALL_BANNER_CANNOT_ANSWER_2026_08_25__ Plus d'enregistrement ici.
        //
        // Ce bloc déclarait la catégorie `MESH_CALL` puis appelait
        // `setNotificationCategories([category])` — un REMPLACEMENT complet, qui
        // effaçait MESSAGE_CATEGORY et CHECKIN_WARNING à chaque appel entrant
        // par le mesh. Et ses actions s'appelaient `ANSWER`/`DECLINE`, alors que
        // `userNotificationCenter(_:didReceive:)` teste `ACCEPT_CALL`/
        // `DECLINE_CALL`: le bouton Répondre de la bannière ne décrochait pas.
        //
        // `MESH_CALL` est désormais enregistrée une fois pour toutes dans
        // `NotificationManager.setupNotificationCategories()`, avec les bons
        // identifiants et aux côtés des autres — voir le commentaire là-bas.
        
        let request = UNNotificationRequest(identifier: "meshCall_\(currentCallId ?? "")", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
        
        OshiLog.call.info("🔔 Local notification shown for mesh call")
    }
    
    private func getContactName(for publicKey: String) -> String? {
        // __ALIAS_KEY_FOLD_2026_08_13__ was an exact dictionary hit, so a mesh call
        // notification showed the key whenever the stored alias and the signalled key
        // disagreed on base64url vs `=` padding.
        // __SHARED_NICKNAME_2026_09_22__ alias local, sinon pseudo partage.
        return MessageManager.lookupContactDisplayName(publicKey)
    }
    
    // MARK: - Ring Tones
    
    private var ringPlayer: AVAudioPlayer?
    
    private func playRingTone(outgoing: Bool) {
        let filename = outgoing ? "outgoing_ring" : "incoming_ring"
        guard let url = Bundle.main.url(forResource: filename, withExtension: "wav") else {
            // Fallback to system sound
            AudioServicesPlaySystemSound(outgoing ? 1151 : 1007)
            return
        }
        
        do {
            ringPlayer = try AVAudioPlayer(contentsOf: url)
            ringPlayer?.numberOfLoops = -1 // Loop
            ringPlayer?.play()
        } catch {
            OshiLog.call.info("⚠️ Could not play ring tone: \(error)")
        }
    }
    
    private func stopRingTone() {
        ringPlayer?.stop()
        ringPlayer = nil
    }
    
    // MARK: - Controls
    
    func toggleMute() {
        isMuted.toggle()
        OshiLog.call.info("🎤 Mute: \(isMuted)")
    }
    
    func toggleSpeaker() {
        isSpeakerOn.toggle()
        
        do {
            let session = AVAudioSession.sharedInstance()
            try session.overrideOutputAudioPort(isSpeakerOn ? .speaker : .none)
            OshiLog.call.info("🔊 Speaker: \(isSpeakerOn)")
        } catch {
            OshiLog.call.info("⚠️ Speaker toggle error: \(error)")
        }
    }
    
    // MARK: - Cleanup
    
    private func handleRingTimeout() {
        OshiLog.call.info("⏰ Call ring timeout")
        callState = .ended
        
        NotificationCenter.default.post(
            name: NSNotification.Name("MeshCallTimeout"),
            object: nil,
            userInfo: ["callId": currentCallId ?? ""]
        )
        
        endCallCleanup()
    }
    
    private func handlePeerDisconnected(_ peerKey: String) {
        if peerKey == remotePeerKey && callState != .idle {
            OshiLog.call.info("📞 Peer disconnected during call")
            callState = .failed
            
            NotificationCenter.default.post(
                name: NSNotification.Name("MeshCallFailed"),
                object: nil,
                userInfo: ["reason": "Peer disconnected"]
            )
            
            endCallCleanup()
        }
    }
    
    private func endCallCleanup() {
        // Store callId before clearing for notification removal
        let callIdForNotification = currentCallId

        // Stop timers
        callTimer?.invalidate()
        callTimer = nil
        keepAliveTimer?.invalidate()
        keepAliveTimer = nil
        ringTimeout?.invalidate()
        ringTimeout = nil
        keepAliveTimeoutTimer?.invalidate()
        keepAliveTimeoutTimer = nil

        // Stop audio playback
        stopAudioPlayback()

        // Stop audio engine
        audioEngine?.inputNode.removeTap(onBus: 0)
        audioEngine?.stop()
        audioEngine = nil
        audioPlayerNode = nil

        // Stop ring
        stopRingTone()

        // Reset audio components
        audioCodec.reset()
        jitterBuffer.reset()
        audioSequenceNumber = 0

        // Reset state
        DispatchQueue.main.async { [weak self] in
            self?.callState = .idle
            self?.currentCallId = nil
            self?.remotePeerKey = nil
            self?.callDuration = 0
            self?.isUsingMeshDirect = false
            self?.isVideoCall = false
            self?.audioQuality = "Good"
        }

        // Deactivate audio session
        try? AVAudioSession.sharedInstance().setActive(false)

        // Remove notification
        if let callId = callIdForNotification {
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["meshCall_\(callId)"])
        }

        OshiLog.call.info("📞 Mesh call cleanup completed (codec & jitter buffer reset)")
    }
    
    private func startKeepAlive() {
        keepAliveTimer = Timer.scheduledTimer(withTimeInterval: keepAliveInterval, repeats: true) { [weak self] _ in
            self?.sendKeepAlive()
        }
    }
    
    private func sendKeepAlive() {
        guard let callId = currentCallId,
              let peerKey = remotePeerKey,
              let identityManager = MessageManager.sharedIdentityManager else { return }
        
        let signal = MeshCallSignal(
            type: .keepAlive,
            callId: callId,
            senderKey: identityManager.publicKey,
            recipientKey: peerKey
        )
        
        sendSignalViaMesh(signal, to: peerKey)
    }
    
    // MARK: - Format Duration
    
    var formattedDuration: String {
        let minutes = Int(callDuration) / 60
        let seconds = Int(callDuration) % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }
}
