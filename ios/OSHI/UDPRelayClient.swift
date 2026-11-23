//
//  UDPRelayClient.swift
//  OSHI
//
//  VPS UDP audio relay client (port 8089).
//
//  Wraps audio in the binary protocol documented in VPS_CONFIGURATION.md:
//      [type(1)][recipientKeyLen(1)][recipientKey][senderKeyLen(1)][senderKey][callIdLen(1)][callId][payload]
//      types: 0x01 = audio, 0x02 = video, 0x04 = register, 0x05 = ping
//
//  Server-side endpoints expire after 30 s of inactivity; we re-send the
//  register packet every 10 s while a call is active to keep ours warm.
//
//  Public keys and the callId are sent as base64url UTF-8 strings — same
//  encoding the WebSocket binary frame uses (`buildBinaryFrame` in
//  VPSClient.kt and the WS register message in VoiceCallManager.swift).
//  base64url(32-byte X25519) is ~43 chars, callId (UUID) is 36 chars, both
//  comfortably below the 255-byte single-byte length cap.
//
//  This transport is wired between P2P and the legacy WebSocket / HTTP
//  fallback in VoiceCallManager.sendAudioPacket. Frames decoded off the
//  socket are piped into the existing receiveAudio() pipeline (with
//  fromP2P=true so the strict-P2P RX gate accepts them — UDP relay is
//  a realtime transport, not a buffered VPS hop).
//

import Foundation
import Network
import os.lock
import CryptoKit

final class UDPRelayClient {

    // MARK: - Configuration

    static let defaultHost: String = "45.67.216.197"
    static let defaultPort: UInt16 = 8089

    /// Server expires endpoints after 30 s of inactivity — re-register
    /// every 10 s while the call is up.
    private static let heartbeatInterval: TimeInterval = 10.0

    // Wire-format type bytes (from VPS_CONFIGURATION.md).
    private static let typeAudio:    UInt8 = 0x01
    private static let typeVideo:    UInt8 = 0x02
    private static let typeRegister: UInt8 = 0x04
    private static let typePing:     UInt8 = 0x05

    // MARK: - State

    private let queue = DispatchQueue(label: "com.oshi.udprelay.client")
    private var connection: NWConnection?
    private var heartbeatTimer: DispatchSourceTimer?

    private var senderKeyB64u: String = ""
    private var callId: String = ""
    private var peerKeyB64u: String = ""
    private var started: Bool = false

    // MARK: - Relay token (__CALL_MEDIA_AUTH_IOS_2026_09_23__, docs/CALL_MEDIA_RELAY_AUTH_CONTRACT.md v1.0)
    //
    // Every 0x04 register we send carries the 44-byte `ORA1` trailer (§3/§4) once a token is
    // held. Without one, a spoofed datagram naming us could re-point our endpoint and divert
    // the peer's media (§1). All of this is `queue`-confined; `macKey` never leaves memory
    // and is never logged.

    /// What `POST /voip/relay-token` returned (§2). `macKey` is a secret: memory only.
    struct RelayToken {
        let tokenId: Data          // 16 raw bytes
        let macKey: SymmetricKey   // 32 bytes
        let expiresAt: Date
    }

    /// Why the relay wants a (new) token from its owner.
    enum TokenRequestReason: String {
        case unknownToken = "nack-01-unknown"     // 04 00 01: expired / revoked / unknown
        case badMac = "nack-02-bad-mac"           // 04 00 02
        case mismatch = "nack-04-mismatch"        // 04 00 04
        case missing = "nack-05/06-no-trailer"    // 04 00 05/06 while we held no token
        case expiring = "expiring"                // < 10 min left (§2 Refresh)
    }

    /// Asked (on `queue`) to fetch a token and hand it back with `setToken(_:)`. The owner
    /// dedups in-flight fetches; we only ask on nacks and near expiry.
    var onTokenNeeded: ((TokenRequestReason) -> Void)?

    private var token: RelayToken?
    /// Last counter placed on the wire for `token`; strictly increasing, reset per token (§3).
    private var tokenCounter: UInt64 = 0
    /// Once the server has nacked us, a token-less register is never sent again (§5).
    private var nackedOnce = false
    /// `04 00 02` twice = our MAC is wrong: stop registering, WS carries the call (§5).
    private var badMacCount = 0
    private var udpAuthGaveUp = false
    private var expiryRefreshRequested = false
    /// Grace window after `start` during which a token-less register is held back while the
    /// token fetch (started at dial / accept) completes — so we are never counted "unsigned".
    private var tokenWaitDeadline: Date = .distantPast
    private static let tokenWaitGrace: TimeInterval = 3.0
    private var lastReplayResendAt: Date = .distantPast

    // 🔧 FIX (2026-07-28, 0x8BADF00D DEADLOCK) — these two readers used to be
    // `queue.sync`. That is what closed a three-way lock cycle and killed the app
    // ~2 min into a video call (OSHI-2026-07-28-005221.ips, scene-update watchdog
    // "is stuck (deadlock)", 0.081 s of app CPU, 0 %):
    //
    //   T1  com.oshi.udprelay.client  holds this queue (handleInboundLocked →
    //       receiveAudio → processReceivedAudioData → playAudioData)
    //       └─ waits on the AVAudioEngine mutex, in -[AVAudioEngine isRunning]
    //   T8  queue "engine"            holds the AVAudioEngine mutex
    //       (IOUnitConfigurationChanged → Stop → Uninitialize)
    //       └─ waits on the RealtimeMessenger mutex
    //   T7  RealtimeMessenger.mServiceQueue   holds the RealtimeMessenger mutex
    //       (the mic tap: processAndSendAudio → sendAudioPacket → udpRelayUsable)
    //       └─ waits on com.oshi.udprelay.client  ← HERE. Cycle closed.
    //
    // Main was a fourth victim, not a participant: the 1 Hz emitDiagPath timer on
    // RunLoop.main also did `isRegistered` → queue.sync onto the same swallowed
    // queue, which is why the report faults on thread 0 and why the beacon stopped
    // at 22:50:39Z while WS RECV kept climbing to #1800 at 22:51:30Z.
    //
    // The trigger was the audio-engine watchdog restart + route change at 22:50:38Z
    // (`ROUTE_APPLY | ctx=engine-watchdog`), which is what puts T8 inside Stop.
    //
    // MECHANISM OF THE FIX: these are two words of state that every hot path polls
    // (the 50 Hz mic tap, the 30 fps VT encoder callback, the 1 Hz main-thread
    // DIAG_PATH). Confining them to the same serial queue that synchronously runs
    // the entire inbound audio pipeline made a cheap read cost an unbounded wait.
    // They now sit behind a LEAF os_unfair_lock — held for two instructions, never
    // held while calling anything — so the T7 leg of the cycle, and main's exposure
    // to it, are both gone. The writers are unchanged and still queue-confined; the
    // lock only replaces the queue as the memory barrier for these two fields.
    //
    // ⚠️ Do NOT reintroduce `queue.sync` in a getter on this class. Anything that
    // reads relay state from the audio tap or from main must be lock-free.
    private var stateLock = os_unfair_lock()
    private var _lastServerReplyAtStorage: Date?
    private var _isRegisteredStorage: Bool = false

    /// Last time the server sent us anything (audio, ack, ping).
    /// Written on `queue`, read from any thread under `stateLock`.
    private var _lastServerReplyAt: Date? {
        get { os_unfair_lock_lock(&stateLock); defer { os_unfair_lock_unlock(&stateLock) }; return _lastServerReplyAtStorage }
        set { os_unfair_lock_lock(&stateLock); _lastServerReplyAtStorage = newValue; os_unfair_lock_unlock(&stateLock) }
    }
    var lastServerReplyAt: Date? { _lastServerReplyAt }

    /// True once the server acked a register with exactly `04 01` (contract §5).
    private var _isRegistered: Bool {
        get { os_unfair_lock_lock(&stateLock); defer { os_unfair_lock_unlock(&stateLock) }; return _isRegisteredStorage }
        set { os_unfair_lock_lock(&stateLock); _isRegisteredStorage = newValue; os_unfair_lock_unlock(&stateLock) }
    }
    var isRegistered: Bool { _isRegistered }

    /// Called for every inbound payload (after header strip). Frames go
    /// straight into VoiceCallManager.receiveAudio. The closure runs on
    /// the internal queue — marshal as needed.
    var onAudioPacket: ((Data) -> Void)?
    var onVideoPacket: ((Data) -> Void)?

    // MARK: - Public API

    /// Open the UDP socket and start sending register heartbeats.
    /// Safe to call repeatedly — only the first call has effect until stop().
    /// `mySenderKey` and `peerKey` are base64-encoded (NOT base64url); we
    /// convert internally so callers don't need to know which encoding the
    /// VPS expects.
    func start(mySenderKey: String, peerKey: String, callId: String,
               token: RelayToken? = nil,
               host: String = UDPRelayClient.defaultHost,
               port: UInt16 = UDPRelayClient.defaultPort) {
        queue.async { [weak self] in
            guard let self = self, !self.started else { return }
            self.started = true
            self.senderKeyB64u = Self.base64urlEncode(mySenderKey)
            self.peerKeyB64u = Self.base64urlEncode(peerKey)
            self.callId = callId
            self._lastServerReplyAt = nil
            self._isRegistered = false
            // __CALL_MEDIA_AUTH_IOS_2026_09_23__
            self.token = token
            self.tokenCounter = 0
            self.nackedOnce = false
            self.badMacCount = 0
            self.udpAuthGaveUp = false
            self.expiryRefreshRequested = false
            self.tokenWaitDeadline = Date().addingTimeInterval(Self.tokenWaitGrace)

            guard let nwPort = NWEndpoint.Port(rawValue: port) else { return }
            let endpoint = NWEndpoint.hostPort(
                host: NWEndpoint.Host(host),
                port: nwPort
            )
            let conn = NWConnection(to: endpoint, using: .udp)
            self.connection = conn
            conn.stateUpdateHandler = { [weak self] state in
                guard let self = self else { return }
                self.queue.async {
                    switch state {
                    case .ready:
                        self.startReceiveLoop(on: conn)
                        self.sendRegisterLocked()
                        self.startHeartbeatLocked()
                        // No token yet: the first register was held back for the
                        // fetch. Retry once the grace window closes rather than
                        // waiting a full 10 s heartbeat (legacy register if the
                        // fetch failed — `dual` still accepts it).
                        let wait = max(0, self.tokenWaitDeadline.timeIntervalSinceNow) + 0.05
                        self.queue.asyncAfter(deadline: .now() + wait) { [weak self] in
                            guard let self = self, self.connection === conn, !self._isRegistered else { return }
                            self.sendRegisterLocked()
                        }
                    case .failed(let err):
                        OshiLog.mesh.info("📡 UDPRelay: NWConnection failed: \(err)")
                    case .cancelled:
                        break
                    default: break
                    }
                }
            }
            conn.start(queue: self.queue)
        }
    }

    /// Stop heartbeat, close the socket, clear state. Safe to call multiple times.
    func stop() {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.heartbeatTimer?.cancel()
            self.heartbeatTimer = nil
            self.connection?.cancel()
            self.connection = nil
            self.started = false
            self._isRegistered = false
            self._lastServerReplyAt = nil
            self.token = nil          // macKey: memory for the call only (§2)
            self.tokenCounter = 0
        }
    }

    /// Install (or replace) the relay token and re-register at once with the trailer.
    /// Counter restarts at 1 for the new token (§2 Refresh, §3).
    func setToken(_ newToken: RelayToken) {
        queue.async { [weak self] in
            guard let self = self, self.started else { return }
            // Same token again: keep its counter (restarting at 1 would be a replay, §3).
            if let cur = self.token, cur.tokenId == newToken.tokenId { return }
            self.token = newToken
            self.tokenCounter = 0
            self.expiryRefreshRequested = false
            if self.connection?.state == .ready { self.sendRegisterLocked() }
        }
    }

    /// Wrap `packet` with the 8089 binary header and send. Returns immediately;
    /// sending happens asynchronously on the internal queue. Drops the frame if
    /// the socket isn't ready yet.
    func sendAudio(_ packet: Data) {
        queue.async { [weak self] in
            guard let self = self, let conn = self.connection else { return }
            let frame = self.buildFrameLocked(type: Self.typeAudio, payload: packet)
            conn.send(content: frame, completion: .contentProcessed { _ in })
        }
    }

    /// Same as `sendAudio` but with the 0x02 video type byte.
    ///
    /// 🔧 FIX (2026-07-27): this used to say "not currently invoked from the call
    /// manager", and that was literally true — `onVideoPacket` was wired for RX but
    /// NO send site ever called this, so on a call where the :8089 relay was the
    /// only working path (the measured cellular device) audio flowed and video did
    /// not. `VoiceCallManager.sendVideoPacketInternal` now nominates this as a
    /// carrier exactly the way `sendAudioPacket` does.
    func sendVideo(_ packet: Data) {
        queue.async { [weak self] in
            guard let self = self, let conn = self.connection else { return }
            let frame = self.buildFrameLocked(type: Self.typeVideo, payload: packet)
            conn.send(content: frame, completion: .contentProcessed { _ in })
        }
    }

    /// Heartbeat helper — exposed for tests / manual prodding.
    func sendRegister() {
        queue.async { [weak self] in self?.sendRegisterLocked() }
    }

    // MARK: - Private — must be called on `queue`

    private func sendRegisterLocked() {
        guard let conn = self.connection else { return }
        guard !udpAuthGaveUp else { return }
        // Register packets carry recipient/sender/callId so the server can map
        // this UDP endpoint to the (publicKey, callId) pair; with a token the
        // payload is the 44-byte ORA1 trailer (contract §3/§4).
        let frame: Data
        if let token = token {
            if !expiryRefreshRequested, token.expiresAt.timeIntervalSinceNow < 10 * 60 {
                expiryRefreshRequested = true
                onTokenNeeded?(.expiring)
            }
            tokenCounter &+= 1
            let header = buildFrameLocked(type: Self.typeRegister, payload: Data())
            frame = Self.appendTrailer(to: header, tokenId: token.tokenId,
                                       counter: tokenCounter, macKey: token.macKey)
        } else {
            // No token. Never token-less after a nack (§5); during the start
            // grace window wait for the fetch instead of being counted unsigned.
            // After it (fetch failed / old server) today's legacy register keeps
            // the call working in `dual`.
            if nackedOnce || Date() < tokenWaitDeadline { return }
            frame = buildFrameLocked(type: Self.typeRegister, payload: Data())
        }
        // `_isRegistered` is set on the server's `04 01` ack (handleInboundLocked),
        // no longer on send completion: a refused register must not look usable.
        conn.send(content: frame, completion: .contentProcessed { _ in })
    }

    private func startHeartbeatLocked() {
        heartbeatTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + Self.heartbeatInterval,
                       repeating: Self.heartbeatInterval)
        timer.setEventHandler { [weak self] in
            self?.sendRegisterLocked()
        }
        heartbeatTimer = timer
        timer.resume()
    }

    private func startReceiveLoop(on conn: NWConnection) {
        conn.receiveMessage { [weak self] data, _, _, error in
            guard let self = self else { return }
            self.queue.async {
                if let data = data, !data.isEmpty {
                    self._lastServerReplyAt = Date()
                    self.handleInboundLocked(data)
                }
                if error == nil && conn.state != .cancelled {
                    self.startReceiveLoop(on: conn)
                }
            }
        }
    }

    /// Inbound frame layout is identical to outbound except the recipient field
    /// is omitted by the server (it's already us). The server emits:
    ///     [type(1)][senderKeyLen(1)][senderKey][callIdLen(1)][callId][payload]
    /// matching the WebSocket binary RX format (see VPSClient.parseBinaryFrame).
    /// For the register-ack the server sends two bytes: [0x04][0x01].
    private func handleInboundLocked(_ data: Data) {
        guard let firstByte = data.first else { return }
        // Register ACK: 2-byte response, no further fields. Treated as proof
        // the server saw us and as a recency stamp for the "demote if stale"
        // policy in VoiceCallManager.
        if firstByte == Self.typeRegister && data.count == 2 {
            _isRegistered = data[data.startIndex + 1] == 0x01
            return
        }
        // Register NACK `04 00 code` (3 bytes, contract §5).
        if let code = Self.parseNack(data) {
            handleNackLocked(code)
            return
        }
        // Audio / video forwarded from the peer.
        guard data.count >= 3 else { return }
        var offset = data.startIndex + 1
        let senderKeyLen = Int(data[offset]); offset += 1
        guard offset + senderKeyLen <= data.endIndex else { return }
        offset += senderKeyLen
        guard offset < data.endIndex else { return }
        let callIdLen = Int(data[offset]); offset += 1
        guard offset + callIdLen <= data.endIndex else { return }
        offset += callIdLen
        let payload: Data
        if offset < data.endIndex {
            payload = data.subdata(in: offset..<data.endIndex)
        } else {
            payload = Data()
        }
        switch firstByte {
        case Self.typeAudio:
            onAudioPacket?(payload)
        case Self.typeVideo:
            onVideoPacket?(payload)
        case Self.typePing:
            // Server liveness ping; nothing to do — recency already updated.
            break
        default:
            break
        }
    }

    /// Contract §5 table, client column.
    private func handleNackLocked(_ code: UInt8) {
        nackedOnce = true
        OshiLog.mesh.info("📡 UDPRelay: register NACK 04 00 0\(code) (token=\(self.token != nil))")
        switch code {
        case 0x01:  // unknown / expired / revoked token → fetch a new one, re-register on setToken
            _isRegistered = false
            token = nil
            onTokenNeeded?(.unknownToken)
        case 0x02:  // bad MAC: bug. Refetch once, then give UDP up (WS carries the call).
            _isRegistered = false
            badMacCount += 1
            token = nil
            if badMacCount >= 2 {
                udpAuthGaveUp = true
                heartbeatTimer?.cancel(); heartbeatTimer = nil
                OshiLog.mesh.info("📡 UDPRelay: bad MAC twice — UDP relay disabled for this call, WS fallback")
            } else {
                onTokenNeeded?(.badMac)
            }
        case 0x03:  // replay: our counter already moved on; resend with the next one.
            if Date().timeIntervalSince(lastReplayResendAt) > 1 {
                lastReplayResendAt = Date()
                sendRegisterLocked()
            }
        case 0x04:  // token is for another (me, peer, callId)
            _isRegistered = false
            token = nil
            onTokenNeeded?(.mismatch)
        case 0x05, 0x06:  // a token-less register was refused: send the trailer
            if token != nil {
                if Date().timeIntervalSince(lastReplayResendAt) > 1 {
                    lastReplayResendAt = Date()
                    sendRegisterLocked()
                }
            } else {
                _isRegistered = false
                onTokenNeeded?(.missing)
            }
        default:
            break
        }
    }

    private func buildFrameLocked(type: UInt8, payload: Data) -> Data {
        Self.buildFrame(type: type, recipient: peerKeyB64u, sender: senderKeyB64u,
                        callId: callId, payload: payload)
    }

    // MARK: - Wire helpers (pure; unit-tested against contract §7 T2/T3)

    static let trailerMagic = Data([0x4f, 0x52, 0x41, 0x31])   // "ORA1"
    static let macDomain = Data("OSHI-UDP-REG-1".utf8)

    /// `[type][recipLen][recipient][senderLen][sender][callIdLen][callId][payload]`.
    static func buildFrame(type: UInt8, recipient: String, sender: String,
                           callId: String, payload: Data) -> Data {
        let recipBytes = recipient.data(using: .utf8) ?? Data()
        let senderBytes = sender.data(using: .utf8) ?? Data()
        let callIdBytes = callId.data(using: .utf8) ?? Data()
        // Lengths are single bytes — the protocol caps each field at 255. All
        // three (base64url X25519, base64url X25519, UUID) are well under that
        // limit, but truncate defensively to avoid a malformed wire frame.
        let recipLen = UInt8(min(recipBytes.count, 255))
        let senderLen = UInt8(min(senderBytes.count, 255))
        let callIdLen = UInt8(min(callIdBytes.count, 255))

        var out = Data(capacity: 4 + Int(recipLen) + Int(senderLen) + Int(callIdLen) + payload.count)
        out.append(type)
        out.append(recipLen)
        if recipLen > 0 { out.append(recipBytes.prefix(Int(recipLen))) }
        out.append(senderLen)
        if senderLen > 0 { out.append(senderBytes.prefix(Int(senderLen))) }
        out.append(callIdLen)
        if callIdLen > 0 { out.append(callIdBytes.prefix(Int(callIdLen))) }
        out.append(payload)
        return out
    }

    /// Append `ORA1 ‖ tokenId(16) ‖ counter(u64 BE) ‖ mac(16)` to a header-only 0x04/0x05
    /// frame, mac = HMAC-SHA256(macKey, "OSHI-UDP-REG-1" ‖ every byte before the mac)[0..<16].
    static func appendTrailer(to frame: Data, tokenId: Data, counter: UInt64,
                              macKey: SymmetricKey) -> Data {
        var out = frame
        out.append(trailerMagic)
        out.append(tokenId)
        var be = counter.bigEndian
        withUnsafeBytes(of: &be) { out.append(contentsOf: $0) }
        var msg = macDomain
        msg.append(out)
        let mac = HMAC<SHA256>.authenticationCode(for: msg, using: macKey)
        out.append(contentsOf: Data(mac).prefix(16))
        return out
    }

    /// `04 00 code` → code; anything else (incl. the `04 01` ack) → nil.
    static func parseNack(_ data: Data) -> UInt8? {
        guard data.count == 3 else { return nil }
        let b = [UInt8](data)
        guard b[0] == typeRegister, b[1] == 0x00 else { return nil }
        return b[2]
    }

    /// base64url (padded or not) → raw bytes.
    static func base64urlDecode(_ s: String) -> Data? {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t.append("=") }
        return Data(base64Encoded: t)
    }

    // MARK: - Encoding helpers

    /// Standard-base64 → base64url (matches VoiceCallManager.base64urlEncode and
    /// Android's VPSClient.publicKeyToBase64url so the server routes by the
    /// exact same key string the WebSocket transport already uses).
    static func base64urlEncode(_ s: String) -> String {
        return s
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
