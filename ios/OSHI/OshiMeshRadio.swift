//
//  OshiMeshRadio.swift
//  OSHI — the app side of OSHI Mesh firmware (github.com/Lastoneparis/oshi-mesh-firmware).
//
//  OSHI Mesh is a Meshtastic fork that stays on the Meshtastic network. On a radio running it, the
//  firmware — not the phone — fragments, acknowledges, repairs and, when the destination is silent,
//  PARKS a message in flash and delivers it once that node is heard again. The phone only hands it a
//  whole message and reads back what became of it.
//
//  WIRE (OSHI Mesh Protocol v1, OMP). Every frame is a PRIVATE_APP (256) payload starting "OS",
//  so it never collides with the app's older "OM" chunking on the same portnum. Little-endian.
//
//    DATA   "OS" 0x11 msgId u32 | origin u32 | dest u32 | idx u8 | count u8 | flags u8 | ≤182 bytes
//    STATUS "OS" 0x16 msgId u32 | state u8 | node u32            (radio → its own phone only)
//    BEACON "OS" 0x15 caps u8 | version u16 | custodyFreeKb u8   (also the capability probe)
//    RADIO  "OS" 0x18 noise i8 | chUtil u8 | airTx u8 | rxGood u32 | rxBad u32 | txGood u32 | txDrop u16
//           (radio → its own phone only, every 30 s; 20 bytes, longer = newer fields appended;
//            noise -128 = no sample yet. Feeds LoRaJammingDetector. Firmware docs/omp/OMP-v1.md §4.7)
//
//  Every fragment but the last is exactly 182 bytes; the firmware rejects anything else. The phone
//  sends its fragments addressed to its OWN node number with origin 0; the radio stamps itself.
//
//  DETECTION. A BEACON sent to our own node is answered by OSHI Mesh firmware and silently dropped
//  by stock firmware (it never echoes a phone packet back). No answer means stock: the PHONE speaks OMP
//  itself — the same DATA frames, stamped with its own node number, broadcast on the private "OSHI"
//  channel (see `oshiChannelPSK`). One wire format on every radio is what lets OSHI Mesh nodes, stock
//  Meshtastic relays and the MeshCore bridge all carry the same message. The older "OM" chunking is
//  still RECEIVED, for apps that predate this.
//
//  Kept byte-for-byte in sync with src/oshi/OshiProtocol.{h,cpp} in the firmware repo;
//  OSHITests/OshiMeshRadioTests.swift pins the same vectors as test/test_oshi_protocol.
//

import Foundation

enum OMP {
    static let magic0: UInt8 = 0x4F   // 'O'
    static let magic1: UInt8 = 0x53   // 'S'
    static let version: UInt8 = 1
    static let dataHeader = 18
    static let maxFragData = 182
    static let maxFrags = 64
    static var maxMessage: Int { maxFragData * maxFrags }
    static let broadcast: UInt32 = 0xFFFF_FFFF

    enum FrameType: UInt8 { case data = 1, sack, custody, receipt, beacon, status, pull, radio }

    enum State: UInt8 {
        case queued = 1, sent, delivered, inCustody, failed, uplinked, rejected
    }

    static let flagCustodyOK: UInt8 = 1 << 0
    static let flagViaCustody: UInt8 = 1 << 1
    static let capCustodian: UInt8 = 1 << 0
    static let capGatewayOnline: UInt8 = 1 << 1

    /// SHA-256("OSHI Mesh channel v1") — identical to OSHI_CHANNEL_PSK in the firmware. Public on purpose:
    /// it only keeps OSHI traffic OFF the default channel, because stock Meshtastic ROUTERs drop
    /// PRIVATE_APP on a channel they can decrypt (`CORE_PORTNUMS_ONLY`) but relay what they cannot
    /// decrypt untouched. Confidentiality is the OSHI envelope's job, as it always was.
    static let oshiChannelName = "OSHI"
    static let oshiChannelPSK = Data([0x29, 0x43, 0x6d, 0x19, 0xaf, 0x55, 0xbd, 0x4e, 0x20, 0x24, 0x73,
                                      0x23, 0xc8, 0xff, 0x45, 0x57, 0x86, 0xf0, 0x03, 0x11, 0x98, 0x17,
                                      0xe1, 0x6c, 0xf7, 0xdf, 0x30, 0x03, 0x68, 0x4c, 0xb9, 0x50])

    struct DataFrame: Equatable {
        var msgId: UInt32
        var origin: UInt32
        var dest: UInt32
        var idx: Int
        var count: Int
        var flags: UInt8
        var payload: Data
    }

    struct Status: Equatable {
        var msgId: UInt32
        var state: State
        var node: UInt32
    }

    struct Beacon: Equatable {
        var caps: UInt8
        var version: UInt16
        var custodyFreeKb: UInt8
    }

    /// __ANTI_JAMMING_PHASE1_2026_11_13__ The RADIO frame: what the radio measures about its channel.
    struct RadioStats: Equatable {
        /// dBm, or nil when the radio has no idle-RSSI sample yet (sent as -128).
        var noiseFloorDbm: Int?
        var channelUtilPct: Int
        var airUtilTxPct: Int
        var rxGood: UInt32
        var rxBad: UInt32
        var txGood: UInt32
        var txDrop: UInt16
    }

    static let radioLen = 20
    static let radioNoiseUnknown = -128

    // MARK: Encoding

    private static func prefix(_ t: FrameType) -> Data {
        Data([magic0, magic1, (version << 4) | t.rawValue])
    }

    private static func le32(_ v: UInt32) -> Data {
        var x = v.littleEndian
        return withUnsafeBytes(of: &x) { Data($0) }
    }

    static func type(of frame: Data) -> FrameType? {
        let b = [UInt8](frame.prefix(3))
        guard b.count == 3, b[0] == magic0, b[1] == magic1, b[2] >> 4 == version else { return nil }
        return FrameType(rawValue: b[2] & 0x0F)
    }

    /// The message as DATA fragments, or nil when it is larger than OMP carries (~11.6 KB).
    /// `origin` 0 lets an OSHI Mesh radio stamp itself; a phone doing OMP itself (stock Meshtastic radio,
    /// MeshCore radio) puts its own node number there, because nothing downstream will.
    static func dataFrames(msgId: UInt32, dest: UInt32, body: Data, flags: UInt8 = 0, origin: UInt32 = 0) -> [Data]? {
        let count = max(1, (body.count + maxFragData - 1) / maxFragData)
        guard count <= maxFrags else { return nil }
        let bytes = [UInt8](body)
        return (0..<count).map { i in
            var f = prefix(.data)
            f.append(le32(msgId))
            f.append(le32(origin))
            f.append(le32(dest))
            f.append(contentsOf: [UInt8(i), UInt8(count), flags])
            let lo = i * maxFragData, hi = min(bytes.count, lo + maxFragData)
            if lo < hi { f.append(contentsOf: bytes[lo..<hi]) }
            return f
        }
    }

    static func beaconProbe() -> Data {
        prefix(.beacon) + Data([0, 0, 0, 0])
    }

    // MARK: Decoding

    private static func u32(_ b: [UInt8], _ o: Int) -> UInt32 {
        UInt32(b[o]) | UInt32(b[o + 1]) << 8 | UInt32(b[o + 2]) << 16 | UInt32(b[o + 3]) << 24
    }

    static func parseData(_ frame: Data) -> DataFrame? {
        guard type(of: frame) == .data, frame.count >= dataHeader, frame.count <= dataHeader + maxFragData else { return nil }
        let b = [UInt8](frame)
        let idx = Int(b[15]), count = Int(b[16])
        guard count > 0, count <= maxFrags, idx < count else { return nil }
        return DataFrame(msgId: u32(b, 3), origin: u32(b, 7), dest: u32(b, 11), idx: idx, count: count,
                         flags: b[17], payload: Data(b[dataHeader...]))
    }

    static func parseStatus(_ frame: Data) -> Status? {
        guard type(of: frame) == .status, frame.count >= 12 else { return nil }
        let b = [UInt8](frame)
        guard let st = State(rawValue: b[7]) else { return nil }
        return Status(msgId: u32(b, 3), state: st, node: u32(b, 8))
    }

    static func parseBeacon(_ frame: Data) -> Beacon? {
        guard type(of: frame) == .beacon, frame.count >= 7 else { return nil }
        let b = [UInt8](frame)
        return Beacon(caps: b[3], version: UInt16(b[4]) | UInt16(b[5]) << 8, custodyFreeKb: b[6])
    }

    static func parseRadio(_ frame: Data) -> RadioStats? {
        guard type(of: frame) == .radio, frame.count >= radioLen else { return nil }
        let b = [UInt8](frame)
        let noise = Int(Int8(bitPattern: b[3]))
        return RadioStats(noiseFloorDbm: noise == radioNoiseUnknown ? nil : noise,
                          channelUtilPct: min(Int(b[4]), 100),
                          airUtilTxPct: min(Int(b[5]), 100),
                          rxGood: u32(b, 6), rxBad: u32(b, 10), txGood: u32(b, 14),
                          txDrop: UInt16(b[18]) | UInt16(b[19]) << 8)
    }

    /// The firmware's encoder, mirrored so tests can pin the layout against the firmware's vector.
    static func radioFrame(_ s: RadioStats) -> Data {
        var f = prefix(.radio)
        let noise = max(-128, min(127, s.noiseFloorDbm ?? radioNoiseUnknown))
        f.append(UInt8(bitPattern: Int8(noise)))
        f.append(UInt8(max(0, min(100, s.channelUtilPct))))
        f.append(UInt8(max(0, min(100, s.airUtilTxPct))))
        f.append(le32(s.rxGood)); f.append(le32(s.rxBad)); f.append(le32(s.txGood))
        f.append(contentsOf: [UInt8(s.txDrop & 0xFF), UInt8(s.txDrop >> 8)])
        return f
    }

    // MARK: Message body

    /// 0x01 = raw, 0x02 = zlib. The envelope JSON carries base64 ciphertext and repeated keys, so deflate
    /// typically saves a third of the airtime — on LoRa, that is a third of every frame.
    static func encodeBody(_ json: Data) -> Data {
        if let z = try? (json as NSData).compressed(using: .zlib) as Data, z.count < json.count {
            return Data([0x02]) + z
        }
        return Data([0x01]) + json
    }

    static func decodeBody(_ body: Data) -> Data? {
        guard let kind = body.first else { return nil }
        let rest = body.dropFirst()
        switch kind {
        case 0x01: return Data(rest)
        case 0x02: return try? (Data(rest) as NSData).decompressed(using: .zlib) as Data
        default: return nil
        }
    }
}

/// Reassembles DATA frames the radio hands us. The radio delivers a message only once it is complete,
/// in order, over a reliable BLE link — this exists for the case where the link drops mid-delivery.
final class OMPReassembler {
    private struct Entry {
        var count: Int
        var parts: [Int: Data]
        var started: Date
    }
    private var entries: [String: Entry] = [:]
    static let ttl: TimeInterval = 300
    static let maxEntries = 8

    /// The complete message body when this fragment finishes it.
    func accept(_ f: OMP.DataFrame, now: Date = Date()) -> Data? {
        entries = entries.filter { now.timeIntervalSince($0.value.started) < Self.ttl }
        let key = "\(f.origin):\(f.msgId)"
        var e = entries[key] ?? Entry(count: f.count, parts: [:], started: now)
        guard e.count == f.count else { return nil }
        if entries[key] == nil, entries.count >= Self.maxEntries,
           let oldest = entries.min(by: { $0.value.started < $1.value.started })?.key {
            entries.removeValue(forKey: oldest)
        }
        e.parts[f.idx] = f.payload
        entries[key] = e
        guard e.parts.count == e.count else { return nil }
        entries.removeValue(forKey: key)
        var full = Data()
        for i in 0..<e.count {
            guard let p = e.parts[i] else { return nil }
            full.append(p)
        }
        return full
    }
}
