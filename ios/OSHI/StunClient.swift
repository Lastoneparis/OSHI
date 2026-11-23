//
//  StunClient.swift
//  OSHI
//
//  Phase 1 — minimal STUN Binding Request / Response parser.
//
//  Sends an RFC 5389 §7.1 Binding Request over UDP to our STUN/TURN server
//  (same coturn instance that provides TURN relay — coturn answers STUN
//  Binding Requests on the same port with no auth required) and parses
//  XOR-MAPPED-ADDRESS (or legacy MAPPED-ADDRESS) from the response.
//
//  No libwebrtc. Pure Network.framework.
//
//  Next phases:
//    Phase 2 — P2PTransport will call discoverReflexiveAddress() on call start
//              and feed the result into the ICE candidate exchange.
//

import Foundation
import Network

final class StunClient {

    // MARK: - Configuration

    /// STUN endpoint (same IP as our TURN server; coturn serves both).
    static let defaultHost: String = "45.67.216.197"
    static let defaultPort: UInt16 = 3478

    /// RFC 5389 — every STUN message carries this in bytes 4..8.
    private static let magicCookie: UInt32 = 0x2112A442

    /// Binding Request type (first 2 bytes of header).
    private static let bindingRequestType: UInt16 = 0x0001
    private static let bindingSuccessResponseType: UInt16 = 0x0101

    /// Attribute types we care about.
    private static let attrMappedAddress: UInt16 = 0x0001
    private static let attrXorMappedAddress: UInt16 = 0x0020

    /// How long we wait for a response before giving up (ms).
    private static let timeoutMs: Int = 1500

    // MARK: - State

    private let queue = DispatchQueue(label: "com.oshi.stun.client")
    private var connection: NWConnection?

    // MARK: - Public API

    /// Discover this device's public (reflexive) IPv4 address and port as seen
    /// by the STUN server.
    ///
    /// - Parameter completion: called with `(ip, port)` on success or `nil` on
    ///   failure (timeout, parse error, network down, etc.). Callback runs on
    ///   an internal queue — marshal to main if you touch UI state.
    func discoverReflexiveAddress(
        host: String = StunClient.defaultHost,
        port: UInt16 = StunClient.defaultPort,
        completion: @escaping ((ip: String, port: Int)?) -> Void
    ) {
        let endpoint = NWEndpoint.hostPort(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(rawValue: port)!
        )
        let conn = NWConnection(to: endpoint, using: .udp)
        self.connection = conn

        let (request, txId) = Self.buildBindingRequest()

        var completed = false
        let complete: ((ip: String, port: Int)?) -> Void = { [weak self] result in
            guard !completed else { return }
            completed = true
            self?.connection?.cancel()
            self?.connection = nil
            completion(result)
        }

        // Timeout guard.
        queue.asyncAfter(deadline: .now() + .milliseconds(Self.timeoutMs)) {
            complete(nil)
        }

        conn.stateUpdateHandler = { state in
            switch state {
            case .ready:
                conn.send(content: request, completion: .contentProcessed { error in
                    if error != nil {
                        complete(nil)
                        return
                    }
                    conn.receiveMessage { data, _, _, recvError in
                        guard recvError == nil, let data = data else {
                            complete(nil)
                            return
                        }
                        if let parsed = Self.parseBindingResponse(data: data, txId: txId) {
                            complete(parsed)
                        } else {
                            complete(nil)
                        }
                    }
                })
            case .failed, .cancelled:
                complete(nil)
            default:
                break
            }
        }

        conn.start(queue: queue)
    }

    // MARK: - STUN encoding

    /// True when `data` is a STUN Binding SUCCESS RESPONSE carrying the RFC 5389
    /// magic cookie. Used by P2PTransport to demux STUN replies arriving on the
    /// MEDIA socket from application packets.
    ///
    /// ⚠️ Do NOT use `(firstByte & 0xC0) == 0` as the discriminator: OSHI's own
    /// packet types (0x05 AAC-ELD, 0x14/0x15/0x16/0x17 audio, 0x0E/0x0F video
    /// upgrade) all satisfy it. The message-type + magic-cookie pair does not
    /// collide, and callers additionally match the 12-byte transaction id.
    static func isBindingSuccessResponse(_ data: Data) -> Bool {
        guard data.count >= 20 else { return false }
        let b = data.startIndex
        let msgType = (UInt16(data[b]) << 8) | UInt16(data[b + 1])
        guard msgType == bindingSuccessResponseType else { return false }
        let cookie = (UInt32(data[b + 4]) << 24)
                   | (UInt32(data[b + 5]) << 16)
                   | (UInt32(data[b + 6]) << 8)
                   |  UInt32(data[b + 7])
        return cookie == magicCookie
    }

    /// Transaction id (bytes 8..20) of a STUN message, or nil if too short.
    static func transactionId(of data: Data) -> Data? {
        guard data.count >= 20 else { return nil }
        let b = data.startIndex
        return data.subdata(in: (b + 8)..<(b + 20))
    }

    /// Build a 20-byte STUN Binding Request with a random 96-bit transaction ID.
    /// Internal (was private) so P2PTransport can emit one from the MEDIA socket —
    /// the NAT mapping that matters is the one belonging to the socket the audio
    /// actually leaves from.
    static func buildBindingRequest() -> (Data, Data) {
        var header = Data(capacity: 20)
        // Message type (2B, BE)
        header.append(UInt8((bindingRequestType >> 8) & 0xFF))
        header.append(UInt8(bindingRequestType & 0xFF))
        // Message length (2B, BE) — 0, no attributes
        header.append(0x00)
        header.append(0x00)
        // Magic cookie (4B, BE)
        header.append(UInt8((magicCookie >> 24) & 0xFF))
        header.append(UInt8((magicCookie >> 16) & 0xFF))
        header.append(UInt8((magicCookie >> 8) & 0xFF))
        header.append(UInt8(magicCookie & 0xFF))
        // Transaction ID (12B random)
        var txId = Data(count: 12)
        _ = txId.withUnsafeMutableBytes { buf -> Int32 in
            guard let p = buf.baseAddress else { return -1 }
            return SecRandomCopyBytes(kSecRandomDefault, 12, p)
        }
        header.append(txId)
        return (header, txId)
    }

    // MARK: - STUN parsing

    /// Internal (was private) so P2PTransport can parse a reply that arrived on
    /// the media socket. Still verifies the transaction id.
    static func parseBindingResponse(data: Data, txId: Data) -> (ip: String, port: Int)? {
        guard data.count >= 20 else { return nil }

        let msgType = (UInt16(data[0]) << 8) | UInt16(data[1])
        guard msgType == bindingSuccessResponseType else { return nil }

        let msgLen = Int((UInt16(data[2]) << 8) | UInt16(data[3]))
        // Verify magic cookie.
        let cookie = (UInt32(data[4]) << 24)
                   | (UInt32(data[5]) << 16)
                   | (UInt32(data[6]) << 8)
                   | UInt32(data[7])
        guard cookie == magicCookie else { return nil }

        // Verify transaction id matches.
        let respTxId = data.subdata(in: 8..<20)
        guard respTxId == txId else { return nil }

        // Walk TLV attributes.
        var i = 20
        let end = min(20 + msgLen, data.count)
        while i + 4 <= end {
            let attrType = (UInt16(data[i]) << 8) | UInt16(data[i + 1])
            let attrLen = Int((UInt16(data[i + 2]) << 8) | UInt16(data[i + 3]))
            let valueStart = i + 4
            let valueEnd = valueStart + attrLen
            guard valueEnd <= end else { break }

            if attrType == attrXorMappedAddress {
                if let parsed = parseXorMappedAddress(data: data, start: valueStart, length: attrLen, txId: txId) {
                    return parsed
                }
            } else if attrType == attrMappedAddress {
                if let parsed = parseMappedAddress(data: data, start: valueStart, length: attrLen) {
                    return parsed
                }
            }

            // STUN attributes are padded to 4-byte boundary.
            let padded = (attrLen + 3) & ~3
            i = valueStart + padded
        }

        return nil
    }

    /// XOR-MAPPED-ADDRESS (RFC 5389 §15.2)
    ///
    /// Phase 2: IPv6 is now supported so cellular-IPv6 peers (T-Mobile etc.)
    /// get a srflx candidate. The v6 address is XOR-decoded as:
    ///   bytes 0..3  ⊕ magic cookie (0x2112A442)
    ///   bytes 4..15 ⊕ transaction id (12 bytes)
    /// and formatted via inet_ntop.
    private static func parseXorMappedAddress(data: Data, start: Int, length: Int, txId: Data? = nil) -> (ip: String, port: Int)? {
        guard length >= 8 else { return nil }
        // byte 0: reserved (ignored)
        // byte 1: family (0x01 = IPv4, 0x02 = IPv6)
        let family = data[start + 1]
        // bytes 2..3: xPort
        let xPort = (UInt16(data[start + 2]) << 8) | UInt16(data[start + 3])
        let port = Int(xPort ^ UInt16((magicCookie >> 16) & 0xFFFF))

        if family == 0x01 {
            guard length >= 8 else { return nil }
            // XOR with magic cookie.
            let b0 = data[start + 4] ^ UInt8((magicCookie >> 24) & 0xFF)
            let b1 = data[start + 5] ^ UInt8((magicCookie >> 16) & 0xFF)
            let b2 = data[start + 6] ^ UInt8((magicCookie >> 8) & 0xFF)
            let b3 = data[start + 7] ^ UInt8(magicCookie & 0xFF)
            return ("\(b0).\(b1).\(b2).\(b3)", port)
        } else if family == 0x02 {
            guard length >= 20 else { return nil }
            var raw = [UInt8](repeating: 0, count: 16)
            // First 4 bytes XOR'd with magic cookie.
            raw[0] = data[start + 4]  ^ UInt8((magicCookie >> 24) & 0xFF)
            raw[1] = data[start + 5]  ^ UInt8((magicCookie >> 16) & 0xFF)
            raw[2] = data[start + 6]  ^ UInt8((magicCookie >> 8)  & 0xFF)
            raw[3] = data[start + 7]  ^ UInt8( magicCookie        & 0xFF)
            // Next 12 bytes XOR'd with transaction id.
            if let tx = txId, tx.count == 12 {
                for i in 0..<12 {
                    raw[4 + i] = data[start + 8 + i] ^ tx[tx.startIndex + i]
                }
            } else {
                // Fall back to un-XOR'd (rare; some legacy servers).
                for i in 0..<12 { raw[4 + i] = data[start + 8 + i] }
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

    /// MAPPED-ADDRESS (legacy, no XOR) — some servers still emit it.
    private static func parseMappedAddress(data: Data, start: Int, length: Int) -> (ip: String, port: Int)? {
        guard length >= 8 else { return nil }
        let family = data[start + 1]
        let port = Int((UInt16(data[start + 2]) << 8) | UInt16(data[start + 3]))
        if family == 0x01 {
            let b0 = data[start + 4]
            let b1 = data[start + 5]
            let b2 = data[start + 6]
            let b3 = data[start + 7]
            return ("\(b0).\(b1).\(b2).\(b3)", port)
        }
        return nil
    }
}
