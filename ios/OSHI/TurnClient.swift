//
//  TurnClient.swift
//  OSHI
//
//  Phase 3 — RFC 5766 TURN client over UDP for relayed call media when direct
//  hole-punching fails (symmetric NAT, CG-NAT, strict corporate firewalls).
//
//  Scope:
//    - Allocate Request / Success Response (unauth then long-term auth on 401)
//    - Refresh Request (lifetime keepalive)
//    - ChannelBind Request / Success Response
//    - ChannelData messages (0x4000-0x7FFF, 4-byte header) for send/receive
//    - STUN message framing with MESSAGE-INTEGRITY + FINGERPRINT as required
//
//  Stubbed / out-of-scope for this phase:
//    - CreatePermission / Send/Data indications (we only use channels since we
//      have exactly one peer per call)
//    - ALTERNATE-SERVER redirection
//    - TCP TURN (turn-tcp://) — not needed for iOS over LTE/Wi-Fi
//    - TLS (turns://) — left for Phase 5 when we add cert pinning
//
//  Auth model: RFC 5389 §15.4 long-term credentials.
//     key = MD5(username ":" realm ":" password)
//     message_integrity = HMAC-SHA1(key, dummy-length-adjusted STUN message)
//  The first Allocate is sent unauth; server replies 401 with REALM + NONCE,
//  we repeat with MESSAGE-INTEGRITY using those values and the precomputed key.
//
//  Concurrency: one internal serial queue. onRelayedPacket fires on that queue.
//

import Foundation
import Network
import CryptoKit

struct AllocatedRelay {
    let relayedAddress: String
    let relayedPort: UInt16
    let lifetimeSeconds: Int
}

final class TurnClient {

    // MARK: - Public API

    /// Called for every inbound ChannelData frame we demux off the UDP socket.
    /// (payload, peerIP, peerPort) — the peer address is recovered from the
    /// channel number → bound peer map.
    var onRelayedPacket: ((Data, String, UInt16) -> Void)?

    /// Optional hook for lifetime logging / UI.
    var onStateChange: ((String) -> Void)?

    /// Fires once per successful ChannelBind ack from the TURN server. Until
    /// this fires for a given channel, ChannelData frames sent on it are
    /// dropped by coturn (RFC 5766 §11.2). The caller is expected to queue
    /// outbound packets for the channel until this callback runs.
    /// Invoked on the internal serial queue.
    var onChannelBound: ((UInt16) -> Void)?

    // MARK: - Configuration

    private let serverHost: String
    private let serverPort: UInt16
    private let username: String
    private let password: String
    /// Phase 5: TURN-over-TLS (TCP) instead of plain UDP. Wire format is identical
    /// (STUN + ChannelData) but framed over a TLS stream — see openConnectionIfNeeded,
    /// the TCP receive loop, and the 4-byte ChannelData padding in send().
    private let useTLS: Bool
    /// Reassembly buffer for the TLS/TCP stream (datagram boundaries don't exist).
    private var rxBuffer = Data()

    // MARK: - Internal state

    private let queue = DispatchQueue(label: "com.oshi.turn.client")
    private var connection: NWConnection?

    private var realm: String?
    private var nonce: String?
    /// HI(username, realm, password) = MD5(user:realm:pass). Cached once realm/
    /// nonce are known so subsequent requests reuse it.
    private var longTermKey: Data?

    /// Pending Allocate completion.
    private var allocateCompletion: ((Result<AllocatedRelay, Error>) -> Void)?
    private var allocateTxId: Data?
    /// Whether we've already performed one unauth round-trip (so a second 401
    /// doesn't recurse forever).
    private var allocateRetriedWithAuth: Bool = false
    /// Bounded count of auth (401/438) retries for the current Allocate.
    private var allocateAuthRetries: Int = 0

    /// ChannelBind bookkeeping — channelNumber → (peerIP, peerPort, lastBindAt).
    private struct ChannelBinding {
        let peerAddress: String
        let peerPort: UInt16
        var lastBoundAt: Date
    }
    private var bindings: [UInt16: ChannelBinding] = [:]

    /// Lifetime (seconds) returned by the server on the last Allocate/Refresh.
    private var lifetimeSeconds: Int = 600
    private var refreshTimer: DispatchSourceTimer?
    private var rebindTimer: DispatchSourceTimer?

    /// 🔧 FIX (2026-07-27) — Refresh ladder state. `sendRefreshRequest()` used to
    /// emit ONE datagram with no retransmit, no deadline and no completion, and
    /// `scheduleRefresh()` was re-armed ONLY from a Refresh success that carried a
    /// LIFETIME attribute. Against a server measured at ~33 % datagram loss that
    /// means one lost Refresh silently expires the allocation at T+lifetime, in
    /// the middle of a call, with nothing in the log. On UDP a single
    /// unacknowledged send is never a guarantee — that is the whole point of the
    /// transport, and Allocate already learned it (see `allocate`).
    ///
    /// `refreshGeneration` makes a retransmit scheduled by an older ladder a
    /// no-op; `refreshOutstanding` is the "still unanswered" latch the retransmits
    /// and the deadline both test.
    private var refreshGeneration: UInt64 = 0
    private var refreshOutstanding: Bool = false
    /// Bounded re-send count for a Refresh the server ERRORED (typically a rotated
    /// nonce). Without a bound, a server that keeps rejecting us spins forever.
    private var refreshErrorRetries: Int = 0
    /// Retransmit offsets for one Refresh ladder, in ms. Mirrors Allocate's.
    private static let refreshRetransmitMs: [Int] = [500, 1000, 1500]
    private static let refreshDeadlineMs: Int = 3000

    /// Outstanding transaction IDs (hex) → method + channel#/nil for demux on
    /// the response. Used for ChannelBind and Refresh.
    private var pendingTransactions: [String: PendingTx] = [:]
    private struct PendingTx {
        let method: UInt16
        let channelNumber: UInt16?
    }

    private var closed: Bool = false

    // MARK: - STUN constants (RFC 5389 + RFC 5766)

    private static let magicCookie: UInt32 = 0x2112A442

    // Message methods
    private static let methodBinding: UInt16    = 0x001
    private static let methodAllocate: UInt16   = 0x003
    private static let methodRefresh: UInt16    = 0x004
    private static let methodChanBind: UInt16   = 0x009
    // Class bits are OR'd into the 14-bit message type.
    private static let classRequest: UInt16     = 0x0000
    private static let classSuccess: UInt16     = 0x0100
    private static let classError:   UInt16     = 0x0110

    // Attributes
    private static let attrMappedAddress:     UInt16 = 0x0001
    private static let attrUsername:          UInt16 = 0x0006
    private static let attrMessageIntegrity:  UInt16 = 0x0008
    private static let attrErrorCode:         UInt16 = 0x0009
    private static let attrLifetime:          UInt16 = 0x000D
    private static let attrXorPeerAddress:    UInt16 = 0x0012
    private static let attrRealm:             UInt16 = 0x0014
    private static let attrNonce:             UInt16 = 0x0015
    private static let attrXorRelayedAddress: UInt16 = 0x0016
    private static let attrRequestedTransport:UInt16 = 0x0019
    private static let attrChannelNumber:     UInt16 = 0x000C
    private static let attrXorMappedAddress:  UInt16 = 0x0020
    private static let attrFingerprint:       UInt16 = 0x8028

    // MARK: - Init

    init(serverHost: String, serverPort: UInt16, username: String, password: String, useTLS: Bool = false) {
        self.serverHost = serverHost
        self.serverPort = serverPort
        self.username = username
        self.password = password
        self.useTLS = useTLS
    }

    // MARK: - Lifecycle

    /// Allocate a relay.
    ///
    /// 🔧 FIX (2026-07-26): this used to be ONE datagram with NO retransmit and NO
    /// deadline (only the TLS path had a timeout). Over UDP to a host measured at
    /// ~33% ICMP loss, a single dropped Allocate meant the completion NEVER fired —
    /// and P2PTransport sets `relayAllocationAttempted = true` before the result is
    /// known, so the call silently ended up with no relay candidate, forever.
    /// Now: retransmit at 500 ms / 1500 ms and fail the completion at 3 s so the
    /// caller can rebuild and retry. Every outcome is logged.
    func allocate(completion: @escaping (Result<AllocatedRelay, Error>) -> Void) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.allocateCompletion = completion
            self.allocateRetriedWithAuth = false
            self.allocateAuthRetries = 0
            self.openConnectionIfNeeded()
            self.sendAllocateRequest(useAuth: false)

            // Retransmits — harmless if the first one landed (STUN transactions
            // are idempotent; the server answers whichever it sees).
            for delayMs in [500, 1500] {
                self.queue.asyncAfter(deadline: .now() + .milliseconds(delayMs)) { [weak self] in
                    guard let self = self, !self.closed, self.allocateCompletion != nil else { return }
                    OshiLog.mesh.info("🧭 TURN: Allocate retransmit (+\(delayMs)ms) — no response yet")
                    self.sendAllocateRequest(useAuth: self.longTermKey != nil)
                }
            }
            // Hard deadline.
            self.queue.asyncAfter(deadline: .now() + .milliseconds(3000)) { [weak self] in
                guard let self = self, let pending = self.allocateCompletion else { return }
                self.allocateCompletion = nil
                OshiLog.mesh.info("🧭 TURN: ⚠️ Allocate TIMED OUT after 3s (no response from \(self.serverHost):\(self.serverPort))")
                pending(.failure(NSError(domain: "TurnClient", code: -1,
                    userInfo: [NSLocalizedDescriptionKey: "Allocate timed out after 3s"])))
            }
        }
    }

    func bindChannel(peerAddress: String, peerPort: UInt16, channelNumber: UInt16) {
        queue.async { [weak self] in
            guard let self = self else { return }
            guard (0x4000...0x7FFF).contains(channelNumber) else {
                OshiLog.mesh.info("🧭 TURN: ⚠️ channel# \(channelNumber) out of range 0x4000-0x7FFF")
                return
            }
            self.bindings[channelNumber] = ChannelBinding(
                peerAddress: peerAddress,
                peerPort: peerPort,
                lastBoundAt: Date()
            )
            self.sendChannelBindRequest(channel: channelNumber,
                                        peerAddress: peerAddress,
                                        peerPort: peerPort)
        }
    }

    func send(data: Data, viaChannel channel: UInt16) {
        queue.async { [weak self] in
            guard let self = self, let conn = self.connection else { return }
            var frame = Data(capacity: 4 + data.count)
            frame.append(UInt8((channel >> 8) & 0xFF))
            frame.append(UInt8(channel & 0xFF))
            let length = UInt16(data.count)
            frame.append(UInt8((length >> 8) & 0xFF))
            frame.append(UInt8(length & 0xFF))
            frame.append(data)
            if self.useTLS {
                // RFC 5766 §11.5: over TCP/TLS, ChannelData MUST be padded to 4 bytes.
                let pad = (4 - (data.count & 3)) & 3
                if pad > 0 { frame.append(Data(repeating: 0, count: pad)) }
            }
            conn.send(content: frame, completion: .contentProcessed { _ in })
        }
    }

    func close() {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.closed = true
            self.refreshTimer?.cancel(); self.refreshTimer = nil
            self.rebindTimer?.cancel(); self.rebindTimer = nil
            // Invalidate any Refresh ladder still in flight (the `closed` guard
            // already short-circuits them, this makes the intent explicit).
            self.refreshGeneration &+= 1
            self.refreshOutstanding = false
            self.connection?.cancel()
            self.connection = nil
            self.bindings.removeAll()
            self.pendingTransactions.removeAll()
            self.rxBuffer.removeAll(keepingCapacity: false)
        }
    }

    // MARK: - Connection

    private func openConnectionIfNeeded() {
        if connection != nil { return }
        guard let port = NWEndpoint.Port(rawValue: serverPort) else { return }
        let host = NWEndpoint.Host(serverHost)
        let conn: NWConnection
        if useTLS {
            // TURN-over-TLS (TCP). TLS validates against the server's real cert
            // (CN=oshi-messenger.com) — so serverHost must be that hostname, not an
            // IP. On the wire it's indistinguishable from HTTPS, so the GFW lets it
            // through where it drops UDP and fingerprints the WebSocket relay.
            let tcp = NWProtocolTCP.Options()
            tcp.noDelay = true
            tcp.connectionTimeout = 8
            let tls = NWProtocolTLS.Options()
            sec_protocol_options_set_tls_server_name(tls.securityProtocolOptions, serverHost)
            conn = NWConnection(host: host, port: port, using: NWParameters(tls: tls, tcp: tcp))
            OshiLog.mesh.info("🧭 TURN: opening TLS relay to \(self.serverHost):\(self.serverPort)")
        } else {
            conn = NWConnection(to: NWEndpoint.hostPort(host: host, port: port), using: .udp)
        }
        self.connection = conn
        conn.stateUpdateHandler = { [weak self] state in
            guard let self = self else { return }
            self.queue.async {
                switch state {
                case .ready:
                    self.startReceiveLoop(on: conn)
                case .failed(let err):
                    OshiLog.mesh.info("🧭 TURN: ⚠️ NWConnection failed: \(err)")
                    self.allocateCompletion?(.failure(err))
                    self.allocateCompletion = nil
                default: break
                }
            }
        }
        conn.start(queue: queue)
    }

    private func startReceiveLoop(on conn: NWConnection) {
        if useTLS { startTCPReceiveLoop(on: conn); return }
        conn.receiveMessage { [weak self] data, _, _, error in
            guard let self = self else { return }
            self.queue.async {
                if let data = data, !data.isEmpty {
                    self.handleInbound(data)
                }
                if error == nil && !self.closed && conn.state != .cancelled {
                    self.startReceiveLoop(on: conn)
                }
            }
        }
    }

    /// TLS/TCP stream receive: append to rxBuffer and frame out complete messages.
    private func startTCPReceiveLoop(on conn: NWConnection) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            self.queue.async {
                if let data = data, !data.isEmpty {
                    self.rxBuffer.append(data)
                    self.drainRxBuffer()
                }
                if error == nil && !isComplete && !self.closed && conn.state != .cancelled {
                    self.startTCPReceiveLoop(on: conn)
                }
            }
        }
    }

    /// Pull complete STUN / ChannelData messages out of the TLS stream buffer.
    /// TCP framing: ChannelData is padded to a 4-byte boundary (RFC 5766 §11.5);
    /// STUN is a 20-byte header + Message-Length (attributes already 4-aligned).
    private func drainRxBuffer() {
        while rxBuffer.count >= 4 {
            let s = rxBuffer.startIndex
            let topTwo = (rxBuffer[s] & 0xC0) >> 6
            let total: Int
            if topTwo == 0b01 {
                let length = Int((UInt16(rxBuffer[s + 2]) << 8) | UInt16(rxBuffer[s + 3]))
                total = 4 + ((length + 3) & ~3)   // pad up to 4
            } else {
                if rxBuffer.count < 20 { break }
                let msgLen = Int((UInt16(rxBuffer[s + 2]) << 8) | UInt16(rxBuffer[s + 3]))
                total = 20 + msgLen
            }
            // Garbage length (a TURN message can't exceed ~64 KB) → resync by dropping.
            if total <= 0 || total > 70_000 { rxBuffer.removeAll(keepingCapacity: false); break }
            if rxBuffer.count < total { break }   // wait for the rest of the message
            let msg = rxBuffer.subdata(in: s..<(s + total))
            rxBuffer = rxBuffer.subdata(in: (s + total)..<rxBuffer.endIndex)
            handleInbound(msg)
        }
    }

    // MARK: - Inbound demux (ChannelData vs STUN)

    private func handleInbound(_ data: Data) {
        // First two bits distinguish (RFC 5766 §11.4):
        //   00 → STUN message
        //   01 → ChannelData  (channel# 0x4000-0x7FFF)
        guard let first = data.first else { return }
        let topTwo = (first & 0xC0) >> 6
        if topTwo == 0b01 {
            handleChannelData(data)
        } else {
            handleStunMessage(data)
        }
    }

    private func handleChannelData(_ data: Data) {
        guard data.count >= 4 else { return }
        let channel = (UInt16(data[data.startIndex]) << 8) | UInt16(data[data.startIndex + 1])
        let length  = Int((UInt16(data[data.startIndex + 2]) << 8) | UInt16(data[data.startIndex + 3]))
        guard data.count >= 4 + length else { return }
        let payload = data.subdata(in: (data.startIndex + 4)..<(data.startIndex + 4 + length))

        guard let binding = bindings[channel] else {
            // Unbound channel — drop.
            return
        }
        onRelayedPacket?(payload, binding.peerAddress, binding.peerPort)
    }

    private func handleStunMessage(_ data: Data) {
        guard data.count >= 20 else { return }
        let msgType = (UInt16(data[data.startIndex]) << 8) | UInt16(data[data.startIndex + 1])
        let msgLen  = Int((UInt16(data[data.startIndex + 2]) << 8) | UInt16(data[data.startIndex + 3]))
        let cookie: UInt32 =
            (UInt32(data[data.startIndex + 4]) << 24) |
            (UInt32(data[data.startIndex + 5]) << 16) |
            (UInt32(data[data.startIndex + 6]) << 8)  |
             UInt32(data[data.startIndex + 7])
        guard cookie == Self.magicCookie else { return }
        let txId = data.subdata(in: (data.startIndex + 8)..<(data.startIndex + 20))

        let method = msgType & 0x000F | ((msgType & 0x00E0) >> 1) | ((msgType & 0x3E00) >> 2)
        _ = method  // We match on msgType directly below.

        let attrs = parseAttributes(data: data, msgLen: msgLen, txId: txId)

        // Dispatch by method+class.
        switch msgType {
        case Self.methodAllocate | Self.classSuccess:
            handleAllocateSuccess(attrs: attrs)
        case Self.methodAllocate | Self.classError:
            handleAllocateError(attrs: attrs)
        case Self.methodRefresh | Self.classSuccess:
            // 🔧 FIX (2026-07-27): re-arm UNCONDITIONALLY. The old line was
            // `if let life = attrs.lifetime { lifetimeSeconds = life; scheduleRefresh() }`
            // — so a server that answered a Refresh WITHOUT echoing LIFETIME
            // (legal: RFC 5766 §7.3 only says the server SHOULD include it)
            // disarmed our refresh timer forever, and the allocation died at
            // T+lifetime with no log at all. The success itself is the signal to
            // re-arm; the attribute only tunes when.
            refreshOutstanding = false
            refreshErrorRetries = 0
            if let life = attrs.lifetime { lifetimeSeconds = life }
            OshiLog.mesh.info("🧭 TURN: ✅ refresh ok — lifetime=\(self.lifetimeSeconds)s (echoed=\(attrs.lifetime != nil)), next in \(max(self.lifetimeSeconds - 60, 30))s")
            scheduleRefresh()
        case Self.methodRefresh | Self.classError:
            // Common case: NONCE rotated. Update and retry — but BOUNDED, and the
            // retry goes through the full ladder so it is retransmitted too.
            refreshOutstanding = false
            if let newRealm = attrs.realm { realm = newRealm }
            if let newNonce = attrs.nonce { nonce = newNonce; recomputeLongTermKey() }
            if refreshErrorRetries < 3 {
                refreshErrorRetries += 1
                OshiLog.mesh.info("🧭 TURN: refresh error \(attrs.errorCode ?? 0) \(attrs.errorReason ?? "") — re-sending with fresh nonce (\(self.refreshErrorRetries)/3)")
                sendRefreshRequest()
            } else {
                OshiLog.mesh.info("🧭 TURN: ⚠️ refresh error \(attrs.errorCode ?? 0) \(attrs.errorReason ?? "") — 3 retries exhausted; retrying the whole ladder in 30 s (allocation lifetime \(self.lifetimeSeconds)s)")
                refreshErrorRetries = 0
                onStateChange?("refresh_error")
                scheduleRefresh(inSeconds: 30)
            }
        case Self.methodChanBind | Self.classSuccess:
            if let tx = pendingTransactions[txId.hexString],
               let ch = tx.channelNumber {
                bindings[ch]?.lastBoundAt = Date()
                OshiLog.mesh.info("🧭 TURN: channel \(String(format: "0x%04X", ch)) bound")
                // Notify the transport: any packets queued while waiting for
                // this ack can now be flushed (see P2PTransport pendingRelayPackets).
                onChannelBound?(ch)
            }
            pendingTransactions.removeValue(forKey: txId.hexString)
        case Self.methodChanBind | Self.classError:
            if let newRealm = attrs.realm { realm = newRealm }
            if let newNonce = attrs.nonce { nonce = newNonce; recomputeLongTermKey() }
            // Re-bind once with fresh nonce.
            if let tx = pendingTransactions[txId.hexString],
               let ch = tx.channelNumber,
               let binding = bindings[ch] {
                pendingTransactions.removeValue(forKey: txId.hexString)
                sendChannelBindRequest(channel: ch,
                                       peerAddress: binding.peerAddress,
                                       peerPort: binding.peerPort)
            }
        default:
            // Ignore Binding/Data indications in this phase.
            break
        }
    }

    // MARK: - Attribute parsing

    private struct ParsedAttrs {
        var realm: String?
        var nonce: String?
        var errorCode: Int?
        var errorReason: String?
        var lifetime: Int?
        var xorRelayed: (ip: String, port: UInt16)?
        var xorMapped: (ip: String, port: UInt16)?
    }

    private func parseAttributes(data: Data, msgLen: Int, txId: Data) -> ParsedAttrs {
        var out = ParsedAttrs()
        var i = data.startIndex + 20
        let end = min(data.startIndex + 20 + msgLen, data.endIndex)
        while i + 4 <= end {
            let attrType = (UInt16(data[i]) << 8) | UInt16(data[i + 1])
            let attrLen  = Int((UInt16(data[i + 2]) << 8) | UInt16(data[i + 3]))
            let valStart = i + 4
            let valEnd   = valStart + attrLen
            guard valEnd <= end else { break }
            let value = data.subdata(in: valStart..<valEnd)

            switch attrType {
            case Self.attrRealm:
                out.realm = String(data: value, encoding: .utf8)
            case Self.attrNonce:
                out.nonce = String(data: value, encoding: .utf8)
            case Self.attrErrorCode:
                if value.count >= 4 {
                    let cls = Int(value[2] & 0x07)
                    let num = Int(value[3])
                    out.errorCode = cls * 100 + num
                    if value.count > 4 {
                        out.errorReason = String(data: value.suffix(from: value.startIndex + 4), encoding: .utf8)
                    }
                }
            case Self.attrLifetime:
                if value.count >= 4 {
                    let l: UInt32 = (UInt32(value[0]) << 24) |
                                    (UInt32(value[1]) << 16) |
                                    (UInt32(value[2]) << 8)  |
                                     UInt32(value[3])
                    out.lifetime = Int(l)
                }
            case Self.attrXorRelayedAddress:
                out.xorRelayed = decodeXorAddress(value: value, txId: txId)
            case Self.attrXorMappedAddress:
                out.xorMapped = decodeXorAddress(value: value, txId: txId)
            default: break
            }

            let padded = (attrLen + 3) & ~3
            i = valStart + padded
        }
        return out
    }

    private func decodeXorAddress(value: Data, txId: Data) -> (ip: String, port: UInt16)? {
        guard value.count >= 8 else { return nil }
        let family = value[value.startIndex + 1]
        let xPort = (UInt16(value[value.startIndex + 2]) << 8) | UInt16(value[value.startIndex + 3])
        let port = xPort ^ UInt16((Self.magicCookie >> 16) & 0xFFFF)

        if family == 0x01 {
            let b0 = value[value.startIndex + 4] ^ UInt8((Self.magicCookie >> 24) & 0xFF)
            let b1 = value[value.startIndex + 5] ^ UInt8((Self.magicCookie >> 16) & 0xFF)
            let b2 = value[value.startIndex + 6] ^ UInt8((Self.magicCookie >> 8)  & 0xFF)
            let b3 = value[value.startIndex + 7] ^ UInt8( Self.magicCookie        & 0xFF)
            return ("\(b0).\(b1).\(b2).\(b3)", port)
        } else if family == 0x02, value.count >= 20 {
            var raw = [UInt8](repeating: 0, count: 16)
            raw[0] = value[value.startIndex + 4] ^ UInt8((Self.magicCookie >> 24) & 0xFF)
            raw[1] = value[value.startIndex + 5] ^ UInt8((Self.magicCookie >> 16) & 0xFF)
            raw[2] = value[value.startIndex + 6] ^ UInt8((Self.magicCookie >> 8)  & 0xFF)
            raw[3] = value[value.startIndex + 7] ^ UInt8( Self.magicCookie        & 0xFF)
            for k in 0..<12 {
                raw[4 + k] = value[value.startIndex + 8 + k] ^ txId[txId.startIndex + k]
            }
            var addr = in6_addr()
            withUnsafeMutableBytes(of: &addr) { dest in
                raw.withUnsafeBytes { src in dest.copyMemory(from: src) }
            }
            var buf = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
            guard inet_ntop(AF_INET6, &addr, &buf, socklen_t(buf.count)) != nil else { return nil }
            return (String(cString: buf), port)
        }
        return nil
    }

    // MARK: - Allocate

    private func sendAllocateRequest(useAuth: Bool) {
        guard let conn = connection else { return }
        let txId = Self.randomBytes(12)
        self.allocateTxId = txId

        // Build attributes: REQUESTED-TRANSPORT=UDP(17). Lifetime default.
        var attrs = Data()
        attrs.append(encodeAttribute(type: Self.attrRequestedTransport,
                                     value: Data([0x11, 0x00, 0x00, 0x00])))  // UDP=17

        if useAuth, let realm = realm, let nonce = nonce {
            attrs.append(encodeAttribute(type: Self.attrUsername,
                                         value: Data(username.utf8)))
            attrs.append(encodeAttribute(type: Self.attrRealm,
                                         value: Data(realm.utf8)))
            attrs.append(encodeAttribute(type: Self.attrNonce,
                                         value: Data(nonce.utf8)))
        }

        let msg = buildStunMessage(
            msgType: Self.methodAllocate | Self.classRequest,
            txId: txId,
            attrs: attrs,
            withMessageIntegrity: useAuth
        )
        conn.send(content: msg, completion: .contentProcessed { _ in })
    }

    private func handleAllocateSuccess(attrs: ParsedAttrs) {
        guard let relayed = attrs.xorRelayed else {
            allocateCompletion?(.failure(NSError(domain: "TurnClient", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Allocate success with no XOR-RELAYED-ADDRESS"])))
            allocateCompletion = nil
            return
        }
        lifetimeSeconds = attrs.lifetime ?? 600
        let result = AllocatedRelay(relayedAddress: relayed.ip,
                                    relayedPort: relayed.port,
                                    lifetimeSeconds: lifetimeSeconds)
        OshiLog.mesh.info("🧭 TURN: ✅ allocated relay=\(relayed.ip):\(relayed.port) lifetime=\(lifetimeSeconds)s")
        onStateChange?("allocated")
        allocateCompletion?(.success(result))
        allocateCompletion = nil
        // Fresh allocation ⇒ fresh Refresh ladder state.
        refreshOutstanding = false
        refreshErrorRetries = 0
        scheduleRefresh()
        scheduleRebindCheck()
    }

    private func handleAllocateError(attrs: ParsedAttrs) {
        // 🔧 FIX: bounded RETRY COUNT instead of a single boolean. With Allocate
        // retransmits in flight, a stale-nonce 401/438 on the second attempt used
        // to hit the `!allocateRetriedWithAuth` guard and fail the whole
        // allocation — silently killing the only off-LAN media path.
        if (attrs.errorCode == 401 || attrs.errorCode == 438), allocateAuthRetries < 3,
           let realm = attrs.realm, let nonce = attrs.nonce {
            self.realm = realm
            self.nonce = nonce
            recomputeLongTermKey()
            allocateAuthRetries += 1
            allocateRetriedWithAuth = true
            OshiLog.mesh.info("🧭 TURN: Allocate \(attrs.errorCode ?? 0) — retrying with auth (attempt \(self.allocateAuthRetries)/3, realm=\(realm))")
            sendAllocateRequest(useAuth: true)
            return
        }
        let code = attrs.errorCode ?? 0
        let reason = attrs.errorReason ?? "unknown"
        // LOUD: a failure here means the call has NO relay path at all. The
        // hardcoded static credentials now return 401 (coturn runs in
        // use-auth-secret mode), so this fires whenever the /voip/turn-creds
        // fetch did not land before the call started.
        OshiLog.mesh.info("🧭 TURN: ❌ Allocate FAILED \(code) \(reason) — NO RELAY PATH for this call (user=\(self.username.prefix(8))…)")
        allocateCompletion?(.failure(NSError(domain: "TurnClient", code: code,
            userInfo: [NSLocalizedDescriptionKey: "Allocate failed: \(reason)"])))
        allocateCompletion = nil
    }

    // MARK: - Refresh

    /// `inSeconds` overrides the lifetime-derived schedule — used to retry sooner
    /// than a full allocation lifetime after an unanswered or errored ladder.
    private func scheduleRefresh(inSeconds: Int? = nil) {
        refreshTimer?.cancel()
        let ahead = inSeconds ?? max(lifetimeSeconds - 60, 30)
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + .seconds(ahead))
        t.setEventHandler { [weak self] in self?.sendRefreshRequest() }
        t.resume()
        refreshTimer = t
    }

    /// Start one Refresh ladder: an initial datagram plus bounded retransmits,
    /// with a hard deadline that LOGS LOUDLY and reschedules instead of leaving
    /// the allocation to lapse in silence. See `refreshGeneration` for why the
    /// retransmits are generation-guarded.
    private func sendRefreshRequest() {
        guard connection != nil, !closed else { return }
        refreshGeneration &+= 1
        let gen = refreshGeneration
        refreshOutstanding = true
        sendRefreshDatagram()

        for delayMs in Self.refreshRetransmitMs {
            queue.asyncAfter(deadline: .now() + .milliseconds(delayMs)) { [weak self] in
                guard let self = self, !self.closed else { return }
                guard self.refreshGeneration == gen, self.refreshOutstanding else { return }
                OshiLog.mesh.info("🧭 TURN: Refresh retransmit (+\(delayMs)ms) — no response yet")
                self.sendRefreshDatagram()
            }
        }

        queue.asyncAfter(deadline: .now() + .milliseconds(Self.refreshDeadlineMs)) { [weak self] in
            guard let self = self, !self.closed else { return }
            guard self.refreshGeneration == gen, self.refreshOutstanding else { return }
            self.refreshOutstanding = false
            // LOUD: this is the failure that used to be completely silent. If the
            // allocation lapses, every frame on the relay path is dropped by
            // coturn and the call goes quiet with no other symptom.
            OshiLog.mesh.info("🧭 TURN: ❌ REFRESH UNANSWERED — \(Self.refreshRetransmitMs.count + 1) attempts over \(Self.refreshDeadlineMs)ms to \(self.serverHost):\(self.serverPort) got nothing. The allocation (lifetime \(self.lifetimeSeconds)s) WILL EXPIRE and relayed media will stop. Retrying in 30 s.")
            self.onStateChange?("refresh_unanswered")
            self.scheduleRefresh(inSeconds: 30)
        }
    }

    /// One Refresh datagram. Split out of `sendRefreshRequest` so a retransmit
    /// re-signs with the CURRENT realm/nonce (which a 438 may have rotated
    /// between attempts) instead of replaying a stale message.
    private func sendRefreshDatagram() {
        guard let conn = connection else { return }
        let txId = Self.randomBytes(12)
        var attrs = Data()
        // LIFETIME = default 600s
        var life = UInt32(600).bigEndian
        var lifeData = Data()
        withUnsafeBytes(of: &life) { lifeData.append(contentsOf: $0) }
        attrs.append(encodeAttribute(type: Self.attrLifetime, value: lifeData))

        if let realm = realm, let nonce = nonce {
            attrs.append(encodeAttribute(type: Self.attrUsername, value: Data(username.utf8)))
            attrs.append(encodeAttribute(type: Self.attrRealm,    value: Data(realm.utf8)))
            attrs.append(encodeAttribute(type: Self.attrNonce,    value: Data(nonce.utf8)))
        }
        let msg = buildStunMessage(
            msgType: Self.methodRefresh | Self.classRequest,
            txId: txId, attrs: attrs,
            withMessageIntegrity: longTermKey != nil
        )
        // NOTE: deliberately NOT registered in `pendingTransactions`. Nothing
        // demuxes a Refresh by transaction id (handleStunMessage matches on
        // msgType for both the success and error class), so registering it only
        // leaked one dictionary entry per retransmit for the life of the call.
        conn.send(content: msg, completion: .contentProcessed { _ in })
    }

    // MARK: - ChannelBind

    private func scheduleRebindCheck() {
        rebindTimer?.cancel()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + .seconds(60), repeating: .seconds(60))
        t.setEventHandler { [weak self] in
            guard let self = self else { return }
            let now = Date()
            for (ch, b) in self.bindings {
                if now.timeIntervalSince(b.lastBoundAt) >= 9 * 60 {  // rebind every 10 min
                    self.sendChannelBindRequest(channel: ch,
                                                peerAddress: b.peerAddress,
                                                peerPort: b.peerPort)
                }
            }
        }
        t.resume()
        rebindTimer = t
    }

    private func sendChannelBindRequest(channel: UInt16, peerAddress: String, peerPort: UInt16) {
        guard let conn = connection else { return }
        let txId = Self.randomBytes(12)

        var attrs = Data()
        // CHANNEL-NUMBER (2 bytes channel# + 2 bytes reserved).
        var chanVal = Data()
        chanVal.append(UInt8((channel >> 8) & 0xFF))
        chanVal.append(UInt8(channel & 0xFF))
        chanVal.append(0x00); chanVal.append(0x00)
        attrs.append(encodeAttribute(type: Self.attrChannelNumber, value: chanVal))

        // XOR-PEER-ADDRESS — XOR in the same way as XOR-MAPPED-ADDRESS.
        if let peerAttr = encodeXorPeerAddress(ip: peerAddress, port: peerPort, txId: txId) {
            attrs.append(encodeAttribute(type: Self.attrXorPeerAddress, value: peerAttr))
        } else {
            OshiLog.mesh.info("🧭 TURN: ⚠️ ChannelBind: invalid peer address \(peerAddress)")
            return
        }

        if let realm = realm, let nonce = nonce {
            attrs.append(encodeAttribute(type: Self.attrUsername, value: Data(username.utf8)))
            attrs.append(encodeAttribute(type: Self.attrRealm,    value: Data(realm.utf8)))
            attrs.append(encodeAttribute(type: Self.attrNonce,    value: Data(nonce.utf8)))
        }

        let msg = buildStunMessage(
            msgType: Self.methodChanBind | Self.classRequest,
            txId: txId, attrs: attrs,
            withMessageIntegrity: longTermKey != nil
        )
        pendingTransactions[txId.hexString] = PendingTx(method: Self.methodChanBind, channelNumber: channel)
        conn.send(content: msg, completion: .contentProcessed { _ in })
    }

    // MARK: - STUN message building

    private func buildStunMessage(
        msgType: UInt16,
        txId: Data,
        attrs: Data,
        withMessageIntegrity: Bool
    ) -> Data {
        var body = attrs

        if withMessageIntegrity, let key = longTermKey {
            // We need to compute HMAC over the full STUN message with a header
            // whose length field ALREADY counts the MESSAGE-INTEGRITY attr
            // (24 bytes: 4B header + 20B HMAC). No FINGERPRINT in this path.
            let integrityPlaceholder = Data(repeating: 0, count: 20)
            let integrityAttr = encodeAttribute(type: Self.attrMessageIntegrity, value: integrityPlaceholder)
            let declaredLen = body.count + integrityAttr.count

            var hdr = Data()
            hdr.append(UInt8((msgType >> 8) & 0xFF))
            hdr.append(UInt8(msgType & 0xFF))
            hdr.append(UInt8((declaredLen >> 8) & 0xFF))
            hdr.append(UInt8(declaredLen & 0xFF))
            hdr.append(UInt8((Self.magicCookie >> 24) & 0xFF))
            hdr.append(UInt8((Self.magicCookie >> 16) & 0xFF))
            hdr.append(UInt8((Self.magicCookie >> 8) & 0xFF))
            hdr.append(UInt8(Self.magicCookie & 0xFF))
            hdr.append(txId)

            // HMAC-SHA1 over (header || body) — body ends just before the
            // MESSAGE-INTEGRITY attr per RFC 5389 §15.4.
            let signedBytes = hdr + body
            let hmac = HMAC<Insecure.SHA1>.authenticationCode(
                for: signedBytes,
                using: SymmetricKey(data: key)
            )
            let hmacData = Data(hmac)
            let realIntegrityAttr = encodeAttribute(type: Self.attrMessageIntegrity, value: hmacData)
            body.append(realIntegrityAttr)

            var out = hdr
            out.append(body)
            return out
        }

        // No integrity.
        var hdr = Data()
        hdr.append(UInt8((msgType >> 8) & 0xFF))
        hdr.append(UInt8(msgType & 0xFF))
        hdr.append(UInt8((body.count >> 8) & 0xFF))
        hdr.append(UInt8(body.count & 0xFF))
        hdr.append(UInt8((Self.magicCookie >> 24) & 0xFF))
        hdr.append(UInt8((Self.magicCookie >> 16) & 0xFF))
        hdr.append(UInt8((Self.magicCookie >> 8) & 0xFF))
        hdr.append(UInt8(Self.magicCookie & 0xFF))
        hdr.append(txId)
        var out = hdr
        out.append(body)
        return out
    }

    private func encodeAttribute(type: UInt16, value: Data) -> Data {
        var out = Data()
        out.append(UInt8((type >> 8) & 0xFF))
        out.append(UInt8(type & 0xFF))
        let len = UInt16(value.count)
        out.append(UInt8((len >> 8) & 0xFF))
        out.append(UInt8(len & 0xFF))
        out.append(value)
        // Pad to 4-byte boundary.
        let pad = (4 - (value.count & 0x3)) & 0x3
        if pad > 0 {
            out.append(Data(repeating: 0, count: pad))
        }
        return out
    }

    private func encodeXorPeerAddress(ip: String, port: UInt16, txId: Data) -> Data? {
        var value = Data()
        // Reserved byte + family.
        value.append(0x00)
        if ip.contains(":") {
            value.append(0x02)
        } else {
            value.append(0x01)
        }
        // xPort.
        let xPort = port ^ UInt16((Self.magicCookie >> 16) & 0xFFFF)
        value.append(UInt8((xPort >> 8) & 0xFF))
        value.append(UInt8(xPort & 0xFF))

        if ip.contains(":") {
            var addr = in6_addr()
            let rc = ip.withCString { inet_pton(AF_INET6, $0, &addr) }
            guard rc == 1 else { return nil }
            var bytes = [UInt8](repeating: 0, count: 16)
            withUnsafeBytes(of: &addr) { src in
                for i in 0..<16 { bytes[i] = src[i] }
            }
            bytes[0] ^= UInt8((Self.magicCookie >> 24) & 0xFF)
            bytes[1] ^= UInt8((Self.magicCookie >> 16) & 0xFF)
            bytes[2] ^= UInt8((Self.magicCookie >> 8)  & 0xFF)
            bytes[3] ^= UInt8( Self.magicCookie        & 0xFF)
            for i in 0..<12 { bytes[4 + i] ^= txId[txId.startIndex + i] }
            value.append(contentsOf: bytes)
        } else {
            var addr = in_addr()
            let rc = ip.withCString { inet_pton(AF_INET, $0, &addr) }
            guard rc == 1 else { return nil }
            var bytes = [UInt8](repeating: 0, count: 4)
            withUnsafeBytes(of: &addr) { src in
                for i in 0..<4 { bytes[i] = src[i] }
            }
            bytes[0] ^= UInt8((Self.magicCookie >> 24) & 0xFF)
            bytes[1] ^= UInt8((Self.magicCookie >> 16) & 0xFF)
            bytes[2] ^= UInt8((Self.magicCookie >> 8)  & 0xFF)
            bytes[3] ^= UInt8( Self.magicCookie        & 0xFF)
            value.append(contentsOf: bytes)
        }
        return value
    }

    // MARK: - Long-term key

    private func recomputeLongTermKey() {
        guard let realm = realm else { longTermKey = nil; return }
        let combined = "\(username):\(realm):\(password)"
        let digest = Insecure.MD5.hash(data: Data(combined.utf8))
        longTermKey = Data(digest)
    }

    // MARK: - Helpers

    private static func randomBytes(_ n: Int) -> Data {
        var d = Data(count: n)
        _ = d.withUnsafeMutableBytes { buf -> Int32 in
            guard let p = buf.baseAddress else { return -1 }
            return SecRandomCopyBytes(kSecRandomDefault, n, p)
        }
        return d
    }
}

// MARK: - Data → hex (local helper)

private extension Data {
    var hexString: String {
        return self.map { String(format: "%02x", $0) }.joined()
    }
}
