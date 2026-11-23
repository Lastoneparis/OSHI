//
//  V2Client+Sync.swift  →  DevSync: own-device DIRECT sync ("direct strict")
//  OSHI — design: docs/OSHI_DEVICE_SYNC_DIRECT.md · contract: docs/fixtures/devsync/
//
//  __DEVSYNC_DIRECT_2026_09_23__
//  This file used to hold `V2SyncAPI`, the client of the `/v2/sync` server archive.
//  Its only caller was the never-instantiated `V2SyncManager` actor, and the design deletes
//  both (§12 phase 1: "delete iOS V2SyncManager/V2SyncAPI"). The file keeps its name
//  only because the OSHI group of the Xcode project is NOT folder-synced: a new file
//  would need a pbxproj edit, and the pbxproj is shared with concurrent work. The module
//  therefore lives in the two files of the dead `/v2/sync` pair: this one (protocol core +
//  vectors) and V2SyncManager.swift (iOS layer). When the pbxproj is free, split them along
//  the three markers into OSHI/DevSync/*.swift:
//
//    DEVSYNC-CORE     pure protocol: Noise_XXpsk0_25519_ChaChaPoly_SHA256 (CryptoKit),
//                     keys/ids, framing, canonical JSON/rev/digest, merge rules,
//                     session state machine, engine, relay hub. A line-by-line port of
//                     the shared Kotlin core (OSHI-Android/.../network/v2/devsync/),
//                     so iOS, Android and Desktop run the same state machine.
//    DEVSYNC-VECTORS  golden-vector check + in-process two-engine loopback (DEBUG
//                     self-test `-OSHISelfTestDevSync`).
//    DEVSYNC-IOS      flag (`/v2/config` devsync_enabled, default off), device key
//                     (Keychain ThisDeviceOnly), LAN (NWListener/NWBrowser,
//                     _oshi-devsync._tcp), relay (signed URLSessionWebSocketTask),
//                     store adapter (import path, no side effects), manager
//                     (foreground only, D12), Linked devices screen.
//
//  No sync data ever touches a server: LAN or a live pass-through relay that only
//  forwards Noise ciphertext between two connected sockets.
//

import Foundation
import CryptoKit
import Security
import Network
import Combine
import SwiftUI
import UIKit
import UserNotifications

// ===DEVSYNC-CORE-BEGIN===
// MARK: - DevSync core (pure Foundation + CryptoKit; no UIKit, no app types)
//
// Contract: docs/OSHI_DEVICE_SYNC_DIRECT.md + docs/fixtures/devsync/ (golden vectors).
// This is a line-by-line port of the shared Kotlin core
// (OSHI-Android/.../network/v2/devsync/) so the three clients speak the same
// state machine: same record order, same pairing rules, same diff.

struct DevSyncError: Error, CustomStringConvertible {
    let description: String
    init(_ s: String) { description = s }
}

// MARK: Crypto primitives

enum DSCrypto {
    static func sha256(_ parts: Data...) -> Data {
        var h = SHA256()
        for p in parts { h.update(data: p) }
        return Data(h.finalize())
    }

    /// HMAC-SHA256. An empty key is the same as HashLen zero bytes (HMAC pads the key).
    static func hmac(_ key: Data, _ data: Data) -> Data {
        let k = key.isEmpty ? Data(count: 32) : key
        return Data(HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: k)))
    }

    /// RFC 5869 HKDF-SHA256; an empty salt means HashLen zero bytes.
    static func hkdf(ikm: Data, salt: Data, info: Data, length: Int) -> Data {
        let prk = hmac(salt.isEmpty ? Data(count: 32) : salt, ikm)
        var out = Data()
        var t = Data()
        var i: UInt8 = 1
        while out.count < length {
            t = hmac(prk, t + info + Data([i]))
            out.append(t)
            i &+= 1
        }
        return Data(out.prefix(length))
    }

    static func x25519Public(_ priv: Data) throws -> Data {
        try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: priv).publicKey.rawRepresentation
    }

    static func x25519(_ priv: Data, _ pub: Data) throws -> Data {
        let k = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: priv)
        let p = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: pub)
        let s = try k.sharedSecretFromKeyAgreement(with: p)
        return s.withUnsafeBytes { Data($0) }
    }

    /// Noise ChaChaPoly nonce: 4 zero bytes || u64 LITTLE-endian counter.
    static func nonce(_ n: UInt64) -> Data {
        var d = Data(count: 4)
        for i in 0..<8 { d.append(UInt8(truncatingIfNeeded: n >> (8 * UInt64(i)))) }
        return d
    }

    static func seal(key: Data, n: UInt64, ad: Data, plaintext: Data) throws -> Data {
        let box = try ChaChaPoly.seal(plaintext, using: SymmetricKey(data: key),
                                      nonce: ChaChaPoly.Nonce(data: nonce(n)), authenticating: ad)
        return box.ciphertext + box.tag
    }

    static func open(key: Data, n: UInt64, ad: Data, ciphertext: Data) throws -> Data {
        guard ciphertext.count >= 16 else { throw DevSyncError("ciphertext shorter than its tag") }
        let c = Data(ciphertext)
        let box = try ChaChaPoly.SealedBox(nonce: ChaChaPoly.Nonce(data: nonce(n)),
                                           ciphertext: c.prefix(c.count - 16), tag: c.suffix(16))
        return try ChaChaPoly.open(box, using: SymmetricKey(data: key), authenticating: ad)
    }

    static func hex(_ d: Data) -> String {
        let digits = Array("0123456789abcdef".utf8)
        var out = [UInt8]()
        out.reserveCapacity(d.count * 2)
        for b in d { out.append(digits[Int(b >> 4)]); out.append(digits[Int(b & 0x0F)]) }
        return String(decoding: out, as: UTF8.self)
    }

    static func unhex(_ s: String) -> Data? {
        let u = Array(s.utf8)
        guard u.count % 2 == 0 else { return nil }
        func v(_ c: UInt8) -> UInt8? {
            switch c {
            case 48...57: return c - 48
            case 97...102: return c - 87
            case 65...70: return c - 55
            default: return nil
            }
        }
        var out = Data(capacity: u.count / 2)
        var i = 0
        while i < u.count {
            guard let hi = v(u[i]), let lo = v(u[i + 1]) else { return nil }
            out.append(hi << 4 | lo)
            i += 2
        }
        return out
    }

    static func random(_ n: Int) -> Data {
        var d = Data(count: n)
        let ok = d.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, n, $0.baseAddress!) }
        if ok != errSecSuccess { for i in 0..<n { d[i] = UInt8.random(in: 0...255) } }
        return d
    }

    static func u32be(_ v: UInt32) -> Data {
        Data([UInt8(v >> 24 & 0xFF), UInt8(v >> 16 & 0xFF), UInt8(v >> 8 & 0xFF), UInt8(v & 0xFF)])
    }

    static func u64be(_ v: UInt64) -> Data {
        var d = Data()
        for i in (0..<8).reversed() { d.append(UInt8(truncatingIfNeeded: v >> (8 * UInt64(i)))) }
        return d
    }
}

// MARK: Noise_XXpsk0_25519_ChaChaPoly_SHA256 (Noise rev 34)

final class DSCipherState {
    private(set) var k: Data?
    private(set) var n: UInt64 = 0

    init(_ k: Data? = nil) { self.k = k }

    func encrypt(ad: Data, _ plaintext: Data) throws -> Data {
        guard let k else { return plaintext }
        guard n < UInt64.max else { throw DevSyncError("nonce exhausted") }
        let ct = try DSCrypto.seal(key: k, n: n, ad: ad, plaintext: plaintext)
        n += 1
        return ct
    }

    func decrypt(ad: Data, _ ciphertext: Data) throws -> Data {
        guard let k else { return ciphertext }
        guard n < UInt64.max else { throw DevSyncError("nonce exhausted") }
        let pt = try DSCrypto.open(key: k, n: n, ad: ad, ciphertext: ciphertext)
        n += 1
        return pt
    }

    /// REKEY(k) = ENCRYPT(k, 2^64-1, empty, 32 zero bytes)[0..32]; n is NOT reset.
    func rekey() throws {
        guard let k else { return }
        self.k = Data(try DSCrypto.seal(key: k, n: UInt64.max, ad: Data(), plaintext: Data(count: 32)).prefix(32))
    }
}

private func dsNoiseHKDF(_ ck: Data, _ ikm: Data) -> (Data, Data, Data) {
    let temp = DSCrypto.hmac(ck, ikm)
    let o1 = DSCrypto.hmac(temp, Data([1]))
    let o2 = DSCrypto.hmac(temp, o1 + Data([2]))
    let o3 = DSCrypto.hmac(temp, o2 + Data([3]))
    return (o1, o2, o3)
}

struct DSSymmetricState {
    static let protocolName = Data("Noise_XXpsk0_25519_ChaChaPoly_SHA256".utf8)
    var h: Data
    var ck: Data
    var cs = DSCipherState()

    init() {
        let name = Self.protocolName
        h = name.count <= 32 ? name + Data(count: 32 - name.count) : DSCrypto.sha256(name)
        ck = h
    }

    mutating func mixKey(_ ikm: Data) {
        let (o1, o2, _) = dsNoiseHKDF(ck, ikm)
        ck = o1
        cs = DSCipherState(o2)
    }

    mutating func mixHash(_ data: Data) { h = DSCrypto.sha256(h, data) }

    mutating func mixKeyAndHash(_ ikm: Data) {
        let (o1, o2, o3) = dsNoiseHKDF(ck, ikm)
        ck = o1
        mixHash(o2)
        cs = DSCipherState(o3)
    }

    mutating func encryptAndHash(_ pt: Data) throws -> Data {
        let ct = try cs.encrypt(ad: h, pt)
        mixHash(ct)
        return ct
    }

    mutating func decryptAndHash(_ ct: Data) throws -> Data {
        let pt = try cs.decrypt(ad: h, ct)
        mixHash(ct)
        return pt
    }

    func split() -> (Data, Data) {
        let (o1, o2, _) = dsNoiseHKDF(ck, Data())
        return (o1, o2)
    }
}

/// One direction of the transport phase with the rekey policy: after each message
/// count += 1 and bytes += len(ciphertext); at a threshold Rekey() and reset both.
final class DSTransportCipher {
    static let rekeyMessages: Int64 = 1 << 20
    static let rekeyBytes: Int64 = 1 << 30
    let cs: DSCipherState
    let maxMessages: Int64
    let maxBytes: Int64
    private(set) var count: Int64 = 0
    private(set) var bytes: Int64 = 0
    private(set) var rekeys = 0

    init(_ cs: DSCipherState, maxMessages: Int64 = rekeyMessages, maxBytes: Int64 = rekeyBytes) {
        self.cs = cs
        self.maxMessages = maxMessages
        self.maxBytes = maxBytes
    }

    private func after(_ ctLen: Int) throws {
        count += 1
        bytes += Int64(ctLen)
        if count >= maxMessages || bytes >= maxBytes {
            try cs.rekey()
            rekeys += 1
            count = 0
            bytes = 0
        }
    }

    func encrypt(_ plaintext: Data) throws -> Data {
        let ct = try cs.encrypt(ad: Data(), plaintext)
        try after(ct.count)
        return ct
    }

    func decrypt(_ ciphertext: Data) throws -> Data {
        let pt = try cs.decrypt(ad: Data(), ciphertext)
        try after(ciphertext.count)
        return pt
    }
}

/// Noise_XXpsk0:  -> psk, e   <- e, ee, s, es   -> s, se
final class DSNoiseHandshake {
    let initiator: Bool
    private let sPriv: Data
    let sPub: Data
    private let ePriv: Data
    let ePub: Data
    private let psk: Data
    private(set) var remoteStatic: Data?
    private(set) var remoteEphemeral: Data?
    private var ss = DSSymmetricState()
    private(set) var step = 0

    init(initiator: Bool, staticPriv: Data, psk: Data, prologue: Data, ephemeralPriv: Data? = nil) throws {
        self.initiator = initiator
        sPriv = staticPriv
        sPub = try DSCrypto.x25519Public(staticPriv)
        let e = ephemeralPriv ?? Curve25519.KeyAgreement.PrivateKey().rawRepresentation
        ePriv = e
        ePub = try DSCrypto.x25519Public(e)
        self.psk = psk
        ss.mixHash(prologue)
    }

    var handshakeHash: Data { ss.h }

    func writeMessage1(_ payload: Data) throws -> Data {
        guard initiator, step == 0 else { throw DevSyncError("bad handshake state") }
        ss.mixKeyAndHash(psk)
        ss.mixHash(ePub)
        ss.mixKey(ePub)
        let out = ePub + (try ss.encryptAndHash(payload))
        step = 1
        return out
    }

    func readMessage1(_ msg: Data) throws -> Data {
        guard !initiator, step == 0 else { throw DevSyncError("bad handshake state") }
        let m = Data(msg)
        guard m.count >= 32 + 16 else { throw DevSyncError("message 1 too short") }
        ss.mixKeyAndHash(psk)
        let re = m.prefix(32)
        remoteEphemeral = Data(re)
        ss.mixHash(re)
        ss.mixKey(re)
        let pt = try ss.decryptAndHash(Data(m.dropFirst(32)))
        step = 1
        return pt
    }

    func writeMessage2(_ payload: Data) throws -> Data {
        guard !initiator, step == 1, let re = remoteEphemeral else { throw DevSyncError("bad handshake state") }
        var out = ePub
        ss.mixHash(ePub)
        ss.mixKey(ePub)
        ss.mixKey(try DSCrypto.x25519(ePriv, re))          // ee
        out += try ss.encryptAndHash(sPub)                   // s
        ss.mixKey(try DSCrypto.x25519(sPriv, re))          // es (responder: DH(s, re))
        out += try ss.encryptAndHash(payload)
        step = 2
        return out
    }

    func readMessage2(_ msg: Data) throws -> Data {
        guard initiator, step == 1 else { throw DevSyncError("bad handshake state") }
        let m = Data(msg)
        guard m.count >= 32 + 48 + 16 else { throw DevSyncError("message 2 too short") }
        let re = Data(m.prefix(32))
        remoteEphemeral = re
        ss.mixHash(re)
        ss.mixKey(re)
        ss.mixKey(try DSCrypto.x25519(ePriv, re))          // ee
        let rs = try ss.decryptAndHash(Data(m[32..<80]))     // s
        remoteStatic = rs
        ss.mixKey(try DSCrypto.x25519(ePriv, rs))          // es (initiator: DH(e, rs))
        let pt = try ss.decryptAndHash(Data(m[80...]))
        step = 2
        return pt
    }

    func writeMessage3(_ payload: Data) throws -> Data {
        guard initiator, step == 2, let re = remoteEphemeral else { throw DevSyncError("bad handshake state") }
        var out = try ss.encryptAndHash(sPub)               // s
        ss.mixKey(try DSCrypto.x25519(sPriv, re))          // se (initiator: DH(s, re))
        out += try ss.encryptAndHash(payload)
        step = 3
        return out
    }

    func readMessage3(_ msg: Data) throws -> Data {
        guard !initiator, step == 2 else { throw DevSyncError("bad handshake state") }
        let m = Data(msg)
        guard m.count >= 48 + 16 else { throw DevSyncError("message 3 too short") }
        let rs = try ss.decryptAndHash(Data(m.prefix(48)))  // s
        remoteStatic = rs
        ss.mixKey(try DSCrypto.x25519(ePriv, rs))          // se (responder: DH(e, rs))
        let pt = try ss.decryptAndHash(Data(m.dropFirst(48)))
        step = 3
        return pt
    }

    /// (send, recv). c1 = initiator -> responder.
    func split(rekeyMessages: Int64 = DSTransportCipher.rekeyMessages,
               rekeyBytes: Int64 = DSTransportCipher.rekeyBytes) -> (DSTransportCipher, DSTransportCipher) {
        let (k1, k2) = ss.split()
        let c1 = DSTransportCipher(DSCipherState(k1), maxMessages: rekeyMessages, maxBytes: rekeyBytes)
        let c2 = DSTransportCipher(DSCipherState(k2), maxMessages: rekeyMessages, maxBytes: rekeyBytes)
        return initiator ? (c1, c2) : (c2, c1)
    }

    static let maxMessage = 65_535
    static let tag = 16
}

// MARK: Keys and identifiers (design §3, §4.3, §5.1)

enum DSKeys {
    static let salt = Data("OSHI-DEVSYNC-v1".utf8)
    static let deviceIdBytes = 16
    static let transportLAN = "lan"
    static let transportRelay = "relay"

    static func psk(_ ik: Data) -> Data {
        DSCrypto.hkdf(ikm: ik, salt: salt, info: Data("noise-psk".utf8), length: 32)
    }

    static func disc(_ ik: Data) -> Data {
        DSCrypto.hkdf(ikm: ik, salt: salt, info: Data("mdns-tag".utf8), length: 32)
    }

    static func deviceId(_ dkPub: Data) -> Data {
        Data(DSCrypto.sha256(Data("OSHI-DEVSYNC-id".utf8), dkPub).prefix(deviceIdBytes))
    }

    static func deviceIdHex(_ dkPub: Data) -> String { DSCrypto.hex(deviceId(dkPub)) }

    static func isDeviceIdHex(_ s: String?) -> Bool {
        guard let s, s.utf8.count == 32 else { return false }
        return s.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
    }

    static func mdnsTag(_ disc: Data, unixSeconds: Int64) -> String {
        let hour = unixSeconds >= 0 ? unixSeconds / 3600 : (unixSeconds - 3599) / 3600
        let msg = Data("t".utf8) + DSCrypto.u64be(UInt64(bitPattern: hour))
        return String(DSCrypto.hex(DSCrypto.hmac(disc, msg)).prefix(16))
    }

    static func acceptedTags(_ disc: Data, unixSeconds: Int64) -> Set<String> {
        [mdnsTag(disc, unixSeconds: unixSeconds), mdnsTag(disc, unixSeconds: unixSeconds - 3600)]
    }

    static func sasCode(_ handshakeHash: Data) -> String {
        let okm = DSCrypto.hkdf(ikm: handshakeHash, salt: Data(), info: Data("OSHI-DEVSYNC-sas".utf8), length: 32)
        let v = UInt32(okm[0]) << 24 | UInt32(okm[1]) << 16 | UInt32(okm[2]) << 8 | UInt32(okm[3])
        let s = String(v % 1_000_000)
        return String(repeating: "0", count: max(0, 6 - s.count)) + s
    }

    /// Relay: "OSHI-DEVSYNC/1\n" || "relay" || "\n" || min(idA,idB) || max(idA,idB) (raw bytes).
    /// LAN: "OSHI-DEVSYNC/1\nlan\n" only.
    static func prologue(_ transport: String, _ idA: Data? = nil, _ idB: Data? = nil) -> Data {
        let head = Data("OSHI-DEVSYNC/1\n".utf8) + Data(transport.utf8) + Data("\n".utf8)
        guard transport != transportLAN, let a = idA, let b = idB else { return head }
        return a.lexicographicallyPrecedes(b) || a == b ? head + a + b : head + b + a
    }
}

// MARK: Framing (design §5-§6, framing.json)

enum DSWire {
    static let kindMsg1 = 1, kindMsg2 = 2, kindMsg3 = 3, kindTransport = 4
    static let recordHeader = 6
    static let maxRecord = DSNoiseHandshake.maxMessage - DSNoiseHandshake.tag   // 65 519
    static let maxBody = maxRecord - recordHeader                             // 65 513
    static let mediaChunkData = 60 * 1024
    static let mediaChunkHeader = 32 + 8 + 1
    static let maxReassembled = 8 * 1024 * 1024
    static let flagContinued = 0x01

    static let HELLO = 0x01, ACK = 0x02, DEVICES = 0x03, APPROVAL = 0x04
    static let SUMMARY_REQ = 0x10, SUMMARY = 0x11, BUCKETS_REQ = 0x12, BUCKETS = 0x13
    static let IDS_REQ = 0x14, IDS = 0x15, WANT = 0x16, MESSAGES = 0x17
    static let CONTACTS = 0x18, PROFILE = 0x19, READ_STATE = 0x1A, LIVE = 0x1B, GROUPS = 0x1C
    static let MEDIA_GET = 0x20, MEDIA_CHUNK = 0x21, MEDIA_ERR = 0x22, BYE = 0x7F

    static func isControl(_ type: Int) -> Bool { type == ACK || type == BYE }

    struct Record { let type: Int; let flags: Int; let seq: UInt32; let body: Data
        var continued: Bool { flags & flagContinued != 0 }
    }

    static func encodeRecord(type: Int, flags: Int, seq: UInt32, body: Data) -> Data {
        var out = Data([UInt8(type & 0xFF), UInt8(flags & 0xFF)])
        out += DSCrypto.u32be(seq)
        out += body
        return out
    }

    static func decodeRecord(_ b: Data) throws -> Record {
        let d = Data(b)
        guard d.count >= recordHeader else { throw DevSyncError("record shorter than its header") }
        let seq = UInt32(d[2]) << 24 | UInt32(d[3]) << 16 | UInt32(d[4]) << 8 | UInt32(d[5])
        return Record(type: Int(d[0]), flags: Int(d[1]), seq: seq, body: Data(d.dropFirst(recordHeader)))
    }

    static func fragment(_ body: Data, maxBody: Int = maxBody) -> [(Int, Data)] {
        let b = Data(body)
        if b.count <= maxBody { return [(0, b)] }
        var out: [(Int, Data)] = []
        var off = 0
        while off < b.count {
            let n = min(maxBody, b.count - off)
            let last = off + n == b.count
            out.append((last ? 0 : flagContinued, Data(b[off..<(off + n)])))
            off += n
        }
        return out
    }

    final class Reassembler {
        private var type = -1
        private var buf = Data()

        func push(_ r: Record) throws -> Data? {
            if type != -1 && r.type != type { throw DevSyncError("interleaved fragmented record") }
            if !r.continued && type == -1 { return r.body }
            type = r.type
            buf.append(r.body)
            if buf.count > maxReassembled { throw DevSyncError("reassembled record too large") }
            if r.continued { return nil }
            let all = buf
            buf = Data()
            type = -1
            return all
        }
    }

    static func lanFrame(_ noise: Data) -> Data {
        Data([UInt8(noise.count >> 8 & 0xFF), UInt8(noise.count & 0xFF)]) + noise
    }

    static func relayFrame(_ deviceId: Data, kind: Int, _ noise: Data) -> Data {
        deviceId + Data([UInt8(kind)]) + noise
    }

    struct RelayFrame { let deviceId: Data; let kind: Int; let noise: Data
        var deviceIdHex: String { DSCrypto.hex(deviceId) }
    }

    static func parseRelayFrame(_ b: Data) -> RelayFrame? {
        let d = Data(b)
        guard d.count >= DSKeys.deviceIdBytes + 1 else { return nil }
        let kind = Int(d[DSKeys.deviceIdBytes])
        guard (1...4).contains(kind) else { return nil }
        return RelayFrame(deviceId: Data(d.prefix(DSKeys.deviceIdBytes)), kind: kind,
                          noise: Data(d.dropFirst(DSKeys.deviceIdBytes + 1)))
    }

    struct MediaChunk { let sha256: Data; let offset: Int64; let last: Bool; let data: Data }

    static func encodeMediaChunk(sha256: Data, offset: Int64, last: Bool, data: Data) -> Data {
        sha256 + DSCrypto.u64be(UInt64(offset)) + Data([last ? 1 : 0]) + data
    }

    static func decodeMediaChunk(_ b: Data) throws -> MediaChunk {
        let d = Data(b)
        guard d.count >= mediaChunkHeader else { throw DevSyncError("media chunk too short") }
        var off: UInt64 = 0
        for i in 0..<8 { off = off << 8 | UInt64(d[32 + i]) }
        return MediaChunk(sha256: Data(d.prefix(32)), offset: Int64(off), last: d[40] != 0,
                          data: Data(d.dropFirst(mediaChunkHeader)))
    }
}

// MARK: JSON helpers (records are UTF-8 JSON; unknown fields ignored)

typealias DSObj = [String: Any]

enum DSJSON {
    static func data(_ o: Any) -> Data {
        (try? JSONSerialization.data(withJSONObject: o, options: [.withoutEscapingSlashes])) ?? Data("{}".utf8)
    }

    static func obj(_ d: Data) -> DSObj? {
        (try? JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed])) as? DSObj
    }

    /// Non-empty string or nil (Kotlin `optStr`).
    static func str(_ o: DSObj, _ k: String) -> String? {
        guard let s = o[k] as? String, !s.isEmpty else { return nil }
        return s
    }

    static func string(_ o: DSObj, _ k: String, _ def: String = "") -> String {
        if let s = o[k] as? String { return s }
        if let n = o[k] as? NSNumber, !isBool(n) { return n.stringValue }
        return def
    }

    static func isBool(_ n: NSNumber) -> Bool { CFGetTypeID(n) == CFBooleanGetTypeID() }

    static func bool(_ o: DSObj, _ k: String, _ def: Bool = false) -> Bool {
        if let n = o[k] as? NSNumber { return n.boolValue }
        if let s = o[k] as? String { return s == "true" }
        return def
    }

    static func boolOrNil(_ o: DSObj, _ k: String) -> Bool? {
        guard let n = o[k] as? NSNumber else { return nil }
        return n.boolValue
    }

    static func int64(_ o: DSObj, _ k: String) -> Int64? {
        if let n = o[k] as? NSNumber, !isBool(n) { return n.int64Value }
        if let s = o[k] as? String { return Int64(s) }
        return nil
    }

    static func arr(_ o: DSObj, _ k: String) -> [Any]? { o[k] as? [Any] }
    static func objs(_ o: DSObj, _ k: String) -> [DSObj] { (o[k] as? [Any] ?? []).compactMap { $0 as? DSObj } }
    static func isoMs(_ o: DSObj, _ k: String) -> Int64? { str(o, k).flatMap(DSTime.parse) }
}

// MARK: Time (canonical ISO-8601 UTC with milliseconds)

enum DSTime {
    static func nowMs() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded(.down)) }
    /// Monotonic milliseconds (debounce / handover TTL; immune to wall-clock jumps).
    static func monotonicMs() -> Int64 { Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000) }

    private static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 { a >= 0 ? a / b : -((-a + b - 1) / b) }

    static func iso(_ ms: Int64) -> String {
        let secs = floorDiv(ms, 1000)
        let milli = ms - secs * 1000
        let days = floorDiv(secs, 86_400)
        let sod = secs - days * 86_400
        let z = days + 719_468
        let era = floorDiv(z, 146_097)
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        var y = yoe + era * 400
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        if m <= 2 { y += 1 }
        func p(_ v: Int64, _ w: Int) -> String {
            let s = String(v)
            return s.count >= w ? s : String(repeating: "0", count: w - s.count) + s
        }
        return "\(p(y, 4))-\(p(m, 2))-\(p(d, 2))T\(p(sod / 3600, 2)):\(p(sod % 3600 / 60, 2)):\(p(sod % 60, 2)).\(p(milli, 3))Z"
    }

    static func utcDay(_ ms: Int64) -> String { String(iso(ms).prefix(10)) }

    private static let fmtFrac: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    private static let fmtPlain = ISO8601DateFormatter()

    /// ISO-8601 with or without fractional seconds, `Z` or an offset.
    static func parse(_ s: String) -> Int64? {
        // Fast exact path: yyyy-MM-ddTHH:mm:ss(.f{1,9})?Z
        let u = Array(s.utf8)
        func num(_ a: Int, _ b: Int) -> Int64? {
            guard b <= u.count else { return nil }
            var v: Int64 = 0
            for i in a..<b { guard (48...57).contains(u[i]) else { return nil }; v = v * 10 + Int64(u[i] - 48) }
            return v
        }
        if u.count >= 20, u[4] == 45, u[7] == 45, u[10] == 84, u[13] == 58, u[16] == 58, u.last == 90,
           let y = num(0, 4), let mo = num(5, 7), let d = num(8, 10), let h = num(11, 13),
           let mi = num(14, 16), let se = num(17, 19), (1...12).contains(mo), (1...31).contains(d) {
            var ms: Int64 = 0
            var ok = true
            if u[19] == 46 {
                let fracEnd = u.count - 1
                if fracEnd <= 20 { ok = false } else {
                    var scale: Int64 = 100
                    for i in 20..<fracEnd {
                        guard (48...57).contains(u[i]) else { ok = false; break }
                        ms += Int64(u[i] - 48) * scale
                        scale /= 10
                    }
                }
            } else if u.count != 20 { ok = false }
            if ok {
                let yy = mo <= 2 ? y - 1 : y
                let era = floorDiv(yy, 400)
                let yoe = yy - era * 400
                let mp = mo > 2 ? mo - 3 : mo + 9
                let doy = (153 * mp + 2) / 5 + d - 1
                let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
                let days = era * 146_097 + doe - 719_468
                return ((days * 86_400) + h * 3600 + mi * 60 + se) * 1000 + ms
            }
        }
        if let d = fmtFrac.date(from: s) ?? fmtPlain.date(from: s) {
            return Int64((d.timeIntervalSince1970 * 1000).rounded())
        }
        return nil
    }
}

// MARK: Canonical JSON (for rev / merges only)

indirect enum CJ {
    case null, bool(Bool), int(Int64), str(String), arr([CJ]), obj([(String, CJ)])

    static func utf8Less(_ a: String, _ b: String) -> Bool { a.utf8.lexicographicallyPrecedes(b.utf8) }
    static func utf8Compare(_ a: String, _ b: String) -> Int {
        if a == b { return 0 }
        return utf8Less(a, b) ? -1 : 1
    }

    var string: String {
        var s = ""
        write(&s)
        return s
    }

    private func write(_ out: inout String) {
        switch self {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .int(let i): out += String(i)
        case .str(let s): CJ.writeStr(s, &out)
        case .arr(let a):
            out += "["
            for (i, x) in a.enumerated() { if i > 0 { out += "," }; x.write(&out) }
            out += "]"
        case .obj(let kv):
            out += "{"
            for (i, p) in kv.sorted(by: { CJ.utf8Less($0.0, $1.0) }).enumerated() {
                if i > 0 { out += "," }
                CJ.writeStr(p.0, &out)
                out += ":"
                p.1.write(&out)
            }
            out += "}"
        }
    }

    private static func writeStr(_ s: String, _ out: inout String) {
        out += "\""
        let hex = Array("0123456789abcdef")
        for sc in s.unicodeScalars {
            switch sc.value {
            case 0x22: out += "\\\""
            case 0x5C: out += "\\\\"
            case 0..<0x20: out += "\\u00"; out.append(hex[Int(sc.value >> 4)]); out.append(hex[Int(sc.value & 0xF)])
            default: out.unicodeScalars.append(sc)
            }
        }
        out += "\""
    }
}

// MARK: Model (export v2 objects + media extension, design §7)

enum DSIds {
    /// Standard padded base64 of a 32-byte key; anything else unchanged.
    static func canonicalKey(_ key: String) -> String {
        let t = key.trimmingCharacters(in: .whitespacesAndNewlines)
        if let d = Data(base64Encoded: t), d.count == 32 { return d.base64EncodedString() }
        var s = t.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while s.hasSuffix("=") { s.removeLast() }
        while s.count % 4 != 0 { s += "=" }
        if let d = Data(base64Encoded: s), d.count == 32 { return d.base64EncodedString() }
        return key
    }

    static func isKey(_ key: String) -> Bool { (Data(base64Encoded: canonicalKey(key))?.count ?? 0) == 32 }

    static func canonicalGroupId(_ s: String) -> String {
        UUID(uuidString: s)?.uuidString.uppercased() ?? s.uppercased()
    }

    static func directConversationId(_ peer: String) -> String { "direct:" + canonicalKey(peer) }
    static func groupConversationId(_ gid: String) -> String { "group:" + canonicalGroupId(gid) }

    /// ASCII-only lowercasing (ids compare case-insensitively, never locale-dependently).
    static func asciiLower(_ s: String) -> String {
        guard s.utf8.contains(where: { (65...90).contains($0) }) else { return s }
        return String(decoding: s.utf8.map { (65...90).contains($0) ? $0 + 32 : $0 }, as: UTF8.self)
    }
}

struct SyncMedia: Equatable {
    var type: String
    var fileName: String? = nil
    var mime: String? = nil
    var size: Int64? = nil
    var sha256: String? = nil
    var omitted: String? = nil
}

struct SyncEdited: Equatable { var text: String; var atMs: Int64? = nil }

struct SyncMessage: Equatable {
    var id: String
    var conversation: String
    var outgoing: Bool
    var senderPublicKey: String
    var senderName: String? = nil
    var timestampMs: Int64
    var text: String? = nil
    var status: String
    var read: Bool
    var edited: SyncEdited? = nil
    var deleted = false
    var replyToId: String? = nil
    var viewOnce = false
    var media: SyncMedia? = nil
    var reactions: [String: [String]] = [:]

    var idKey: String { DSIds.asciiLower(id) }
}

struct SyncConversation: Equatable {
    static let kindDirect = "direct", kindGroup = "group", kindDesktopLocal = "desktop.local"
    var id: String
    var kind: String
    var peerPublicKey: String? = nil
    var groupId: String? = nil
    var groupName: String? = nil
    var nativeId: String? = nil

    static func direct(_ peer: String) -> SyncConversation {
        let k = DSIds.canonicalKey(peer)
        return SyncConversation(id: DSIds.directConversationId(k), kind: kindDirect, peerPublicKey: k)
    }

    static func group(_ gid: String, name: String? = nil) -> SyncConversation {
        let g = DSIds.canonicalGroupId(gid)
        return SyncConversation(id: "group:" + g, kind: kindGroup, groupId: g, groupName: name)
    }

    static func desktopLocal(_ nativeId: String) -> SyncConversation {
        SyncConversation(id: "desktop:" + nativeId, kind: kindDesktopLocal, nativeId: nativeId)
    }
}

enum SyncCodec {
    static let statuses: Set<String> = ["pending", "sent", "delivered", "read", "failed"]

    static func normaliseStatus(_ s: String?, outgoing: Bool) -> String {
        if let s, statuses.contains(s) { return s }
        return outgoing ? "sent" : "delivered"
    }

    static func conversationToJson(_ c: SyncConversation) -> DSObj {
        var o: DSObj = ["id": c.id, "kind": c.kind]
        if let v = c.peerPublicKey { o["peerPublicKey"] = v }
        if let v = c.groupId { o["groupId"] = v }
        if let v = c.groupName { o["groupName"] = v }
        if let v = c.nativeId { o["nativeId"] = v }
        return o
    }

    static func conversationFromJson(_ o: DSObj) -> SyncConversation? {
        guard let kind = DSJSON.str(o, "kind") else { return nil }
        switch kind {
        case SyncConversation.kindDirect: return DSJSON.str(o, "peerPublicKey").map { SyncConversation.direct($0) }
        case SyncConversation.kindGroup:
            return DSJSON.str(o, "groupId").map { SyncConversation.group($0, name: DSJSON.str(o, "groupName")) }
        case SyncConversation.kindDesktopLocal: return DSJSON.str(o, "nativeId").map { SyncConversation.desktopLocal($0) }
        default: return nil
        }
    }

    static func messageToJson(_ m: SyncMessage) -> DSObj {
        var o: DSObj = [
            "id": m.id, "conversation": m.conversation, "direction": m.outgoing ? "out" : "in",
            "senderPublicKey": m.senderPublicKey, "timestamp": DSTime.iso(m.timestampMs),
            "status": m.status, "read": m.read,
        ]
        if let v = m.senderName { o["senderName"] = v }
        if !m.deleted, let t = m.text { o["text"] = t }
        if !m.deleted, let e = m.edited {
            var eo: DSObj = ["text": e.text]
            if let at = e.atMs { eo["at"] = DSTime.iso(at) }
            o["edited"] = eo
        }
        if m.deleted { o["deleted"] = true }
        if let r = m.replyToId { o["replyToId"] = r }
        if m.viewOnce { o["viewOnce"] = true }
        if let md = m.media {
            var mo: DSObj = ["type": md.type]
            if let v = md.fileName { mo["fileName"] = v }
            if let v = md.mime { mo["mime"] = v }
            if let v = md.size { mo["size"] = NSNumber(value: v) }
            if let v = md.sha256 { mo["sha256"] = v }
            if let v = md.omitted { mo["omitted"] = v }
            o["media"] = mo
        }
        if !m.deleted, !m.reactions.isEmpty {
            var ro: DSObj = [:]
            for (emoji, keys) in m.reactions where !keys.isEmpty { ro[emoji] = keys.sorted(by: CJ.utf8Less) }
            if !ro.isEmpty { o["reactions"] = ro }
        }
        return o
    }

    static func messageFromJson(_ o: DSObj, conv: SyncConversation) -> SyncMessage? {
        guard let direction = o["direction"] as? String, direction == "in" || direction == "out",
              let id = o["id"] as? String, !id.trimmingCharacters(in: .whitespaces).isEmpty,
              let sender = o["senderPublicKey"] as? String,
              let tsS = o["timestamp"] as? String, let ts = DSTime.parse(tsS) else { return nil }
        let outgoing = direction == "out"
        let deleted = DSJSON.bool(o, "deleted")
        var edited: SyncEdited? = nil
        if let e = o["edited"] as? DSObj { edited = SyncEdited(text: DSJSON.str(e, "text") ?? "", atMs: DSJSON.isoMs(e, "at")) }
        var media: SyncMedia? = nil
        if let md = o["media"] as? DSObj {
            guard let type = md["type"] as? String else { return nil }
            var sha = DSJSON.str(md, "sha256")?.lowercased()
            if let s = sha, !(s.utf8.count == 64 && s.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) })) { sha = nil }
            media = SyncMedia(type: type, fileName: DSJSON.str(md, "fileName"), mime: DSJSON.str(md, "mime"),
                              size: DSJSON.int64(md, "size"), sha256: sha, omitted: DSJSON.str(md, "omitted"))
        }
        var reactions: [String: [String]] = [:]
        if let r = o["reactions"] as? DSObj {
            for (emoji, v) in r {
                guard let arr = v as? [Any] else { continue }
                var seen = Set<String>(); var keys: [String] = []
                for k in arr { if let s = k as? String { let c = DSIds.canonicalKey(s); if seen.insert(c).inserted { keys.append(c) } } }
                reactions[emoji] = keys
            }
        }
        return SyncMessage(
            id: id, conversation: conv.id, outgoing: outgoing, senderPublicKey: DSIds.canonicalKey(sender),
            senderName: DSJSON.str(o, "senderName"), timestampMs: ts,
            text: deleted ? nil : DSJSON.str(o, "text"),
            status: normaliseStatus(DSJSON.str(o, "status"), outgoing: outgoing),
            read: DSJSON.bool(o, "read"), edited: deleted ? nil : edited, deleted: deleted,
            replyToId: DSJSON.str(o, "replyToId"), viewOnce: DSJSON.bool(o, "viewOnce"),
            media: media, reactions: deleted ? [:] : reactions)
    }

    static func encodeBatch(_ convs: [SyncConversation], _ msgs: [SyncMessage]) -> DSObj {
        ["conversations": convs.map(conversationToJson), "messages": msgs.map(messageToJson)]
    }

    struct Batch { var conversations: [String: SyncConversation]; var messages: [SyncMessage]; var invalid: Int; var unknownKind: Int }

    static func decodeBatch(_ o: DSObj) -> Batch {
        var convs: [String: SyncConversation] = [:]
        var declared: [String: String] = [:]
        var unknownKinds = Set<String>()
        for c in DSJSON.objs(o, "conversations") {
            let localId = DSJSON.string(c, "id")
            guard let parsed = conversationFromJson(c) else { if !localId.isEmpty { unknownKinds.insert(localId) }; continue }
            convs[parsed.id] = parsed
            if !localId.isEmpty { declared[localId] = parsed.id }
        }
        var msgs: [SyncMessage] = []
        var invalid = 0, unknown = 0
        for raw in (o["messages"] as? [Any] ?? []) {
            guard let m = raw as? DSObj else { invalid += 1; continue }
            let cid = DSJSON.string(m, "conversation")
            if unknownKinds.contains(cid) { unknown += 1; continue }
            guard let canon = declared[cid], let conv = convs[canon] else { invalid += 1; continue }
            if let p = messageFromJson(m, conv: conv) { msgs.append(p) } else { invalid += 1 }
        }
        return Batch(conversations: convs, messages: msgs, invalid: invalid, unknownKind: unknown)
    }

    static func editedCJ(_ e: SyncEdited) -> CJ {
        var kv: [(String, CJ)] = [("text", .str(e.text))]
        if let at = e.atMs { kv.append(("at", .str(DSTime.iso(at)))) }
        return .obj(kv)
    }

    static func revObject(_ m: SyncMessage) -> CJ {
        let deleted = m.deleted
        var edited = CJ.null
        if !deleted, let e = m.edited { edited = editedCJ(e) }
        var reactions: [(String, CJ)] = []
        if !deleted {
            for (emoji, keys) in m.reactions {
                let ks = Array(Set(keys)).sorted(by: CJ.utf8Less)
                if !ks.isEmpty { reactions.append((emoji, .arr(ks.map { .str($0) }))) }
            }
        }
        return .obj([("deleted", .bool(deleted)), ("edited", edited), ("reactions", .obj(reactions)),
                     ("read", .bool(m.read)), ("status", m.outgoing ? .str(m.status) : .null)])
    }

    static func canonicalRev(_ m: SyncMessage) -> String { revObject(m).string }

    static func rev(_ m: SyncMessage) -> String {
        String(DSCrypto.hex(DSCrypto.sha256(Data(canonicalRev(m).utf8))).prefix(16))
    }

    static func digest(_ pairs: [(String, String)]) -> String {
        let lines = pairs.map { DSIds.asciiLower($0.0) + ":" + $0.1 }.sorted(by: CJ.utf8Less)
        return DSCrypto.hex(DSCrypto.sha256(Data(lines.joined(separator: "\n").utf8)))
    }
}

// MARK: Records (HELLO, DEVICES, CONTACTS, GROUPS, PROFILE, READ_STATE)

struct DSHello {
    static let mediaAlways = "always", mediaUnmetered = "unmetered", mediaNever = "never"
    static let defaultCaps = ["text", "media", "contacts", "profile", "read", "groups", "devices"]
    var deviceId: String
    var name: String
    var platform: String
    var app = ""
    var caps = defaultCaps
    var historySource = false
    var metered = false
    var lowPower = false
    var media = mediaAlways
    var proto = 1
    /// Additive fields carried verbatim (Appendix C §10): e.g. `{"dsk": …}`, the per-device
    /// mailbox signing key, so an approver can sign `APPROVAL.mailbox` for this device.
    var extra: DSObj? = nil
    /// The full decoded HELLO (for `onPeerHello`).
    var raw: DSObj? = nil
    var dsk: String? { raw.flatMap { DSJSON.str($0, "dsk") } ?? extra.flatMap { DSJSON.str($0, "dsk") } }

    func json() -> DSObj {
        var o: DSObj = extra ?? [:]
        let base: DSObj = ["proto": proto, "deviceId": deviceId, "name": name, "platform": platform, "app": app,
                           "caps": caps, "historySource": historySource,
                           "net": ["metered": metered, "lowPower": lowPower], "media": media]
        for (k, v) in base { o[k] = v }
        return o
    }

    func encode() -> Data { DSJSON.data(json()) }

    static func decode(_ b: Data) -> DSHello? {
        guard let o = DSJSON.obj(b), let id = o["deviceId"] as? String else { return nil }
        let net = o["net"] as? DSObj ?? [:]
        return DSHello(deviceId: id.lowercased(), name: String(DSJSON.string(o, "name").prefix(64)),
                       platform: String(DSJSON.string(o, "platform", "unknown").prefix(16)),
                       app: String(DSJSON.string(o, "app").prefix(32)),
                       caps: (o["caps"] as? [Any] ?? []).compactMap { $0 as? String },
                       historySource: DSJSON.bool(o, "historySource"),
                       metered: DSJSON.bool(net, "metered"), lowPower: DSJSON.bool(net, "lowPower"),
                       media: DSJSON.string(o, "media", mediaAlways), proto: Int(DSJSON.int64(o, "proto") ?? 1),
                       raw: o)
    }
}

struct DSLinkedDevice: Equatable {
    var deviceId: String
    var dk: String
    var name: String
    var platform: String
    var approvedAtMs: Int64
    var approvedBy: String? = nil
    var lastSyncAtMs: Int64? = nil

    func wire() -> DSObj {
        ["deviceId": deviceId, "dk": dk, "name": name, "platform": platform, "approvedAt": DSTime.iso(approvedAtMs)]
    }

    func storage() -> DSObj {
        var o = wire()
        if let v = approvedBy { o["approvedBy"] = v }
        if let v = lastSyncAtMs { o["lastSyncAt"] = DSTime.iso(v) }
        return o
    }

    var isConsistent: Bool {
        guard DSKeys.isDeviceIdHex(deviceId), let raw = DSCrypto.unhex(dk) else { return false }
        return DSKeys.deviceIdHex(raw) == deviceId
    }

    static func from(_ o: DSObj) -> DSLinkedDevice? {
        guard let id = o["deviceId"] as? String, let dk = o["dk"] as? String else { return nil }
        return DSLinkedDevice(deviceId: id.lowercased(), dk: dk.lowercased(),
                              name: String(DSJSON.string(o, "name").prefix(64)),
                              platform: String(DSJSON.string(o, "platform", "unknown").prefix(16)),
                              approvedAtMs: DSJSON.isoMs(o, "approvedAt") ?? 0,
                              approvedBy: DSJSON.str(o, "approvedBy"), lastSyncAtMs: DSJSON.isoMs(o, "lastSyncAt"))
    }
}

struct DSDevicesRecord {
    var linked: [DSLinkedDevice]
    var revoked: [String]

    func json() -> DSObj { ["linked": linked.map { $0.wire() }, "revoked": revoked] }

    static func from(_ o: DSObj) -> DSDevicesRecord {
        let linked = DSJSON.objs(o, "linked").compactMap(DSLinkedDevice.from)
        let revoked = (o["revoked"] as? [Any] ?? []).compactMap { ($0 as? String)?.lowercased() }.filter { DSKeys.isDeviceIdHex($0) }
        return DSDevicesRecord(linked: linked, revoked: revoked)
    }
}

struct SyncContact: Equatable {
    var publicKey: String
    var alias: String? = nil
    var aliasAtMs: Int64? = nil
    var blocked: Bool? = nil
    var blockedAtMs: Int64? = nil
    var verified: Bool? = nil
    var verifiedAtMs: Int64? = nil

    func json() -> DSObj {
        var o: DSObj = ["publicKey": publicKey]
        if let v = alias { o["alias"] = v }
        if let v = aliasAtMs { o["aliasAt"] = DSTime.iso(v) }
        if let v = blocked { o["blocked"] = v }
        if let v = blockedAtMs { o["blockedAt"] = DSTime.iso(v) }
        if let v = verified { o["verified"] = v }
        if let v = verifiedAtMs { o["verifiedAt"] = DSTime.iso(v) }
        return o
    }

    static func from(_ o: DSObj) -> SyncContact? {
        guard let pk = o["publicKey"] as? String else { return nil }
        return SyncContact(publicKey: DSIds.canonicalKey(pk), alias: DSJSON.str(o, "alias"), aliasAtMs: DSJSON.isoMs(o, "aliasAt"),
                           blocked: DSJSON.boolOrNil(o, "blocked"), blockedAtMs: DSJSON.isoMs(o, "blockedAt"),
                           verified: DSJSON.boolOrNil(o, "verified"), verifiedAtMs: DSJSON.isoMs(o, "verifiedAt"))
    }
}

struct SyncGroupKey: Equatable {
    static let kindLegacyAes = "legacy-aes", kindV2Epoch = "v2-epoch"
    var kind: String
    var version: Int64
    var key: String
    func json() -> DSObj { ["kind": kind, "version": NSNumber(value: version), "key": key] }
    var cj: CJ { .obj([("key", .str(key)), ("kind", .str(kind)), ("version", .int(version))]) }
}

struct SyncAvatar: Equatable {
    var sha256: String
    var size: Int64
    var mime: String
    func json() -> DSObj { ["sha256": sha256, "size": NSNumber(value: size), "mime": mime] }
    var cj: CJ { .obj([("mime", .str(mime)), ("sha256", .str(sha256)), ("size", .int(size))]) }
}

struct SyncGroup: Equatable {
    var groupId: String
    var name: String
    var nameAtMs: Int64? = nil
    var avatar: SyncAvatar? = nil
    var avatarAtMs: Int64? = nil
    var members: [String] = []
    var admins: [String] = []
    var epoch: Int64 = 0
    var epochAtMs: Int64? = nil
    var left = false
    var leftAtMs: Int64? = nil
    /// __DEVSYNC_REJOIN_2026_09_23__ last deliberate join / rejoin (§8.4 rejoin rule, C.16); nil = unknown.
    var joinedAtMs: Int64? = nil
    var keys: [SyncGroupKey] = []
    var updatedAtMs: Int64
    var description: String? = nil
    var type: String? = nil
    var creator: String? = nil
    var avatarEmoji: String? = nil
    var pinnedMessageId: String? = nil
    var pinnedBy: String? = nil
    /// __GROUP_MUTE_SYNC_2026_09_24__ the account's per-group mute (optional, additive: a peer that
    /// predates it sends neither field and ignores them). Later `mutedAtMs` wins (`SyncMerge.group`).
    var muted: Bool? = nil
    var mutedAtMs: Int64? = nil

    var conversationId: String { DSIds.groupConversationId(groupId) }

    static func keyOrder(_ a: SyncGroupKey, _ b: SyncGroupKey) -> Bool {
        if a.kind != b.kind { return CJ.utf8Less(a.kind, b.kind) }
        if a.version != b.version { return a.version < b.version }
        return CJ.utf8Less(a.key, b.key)
    }

    /// Sorted unique members/admins, sorted keys, and a leave older than `joinedAtMs` dropped (C.16).
    func canonical() -> SyncGroup {
        var c = self
        (c.left, c.leftAtMs) = SyncMerge.leftState([self], joinedAt: joinedAtMs)
        c.members = Array(Set(members)).sorted(by: CJ.utf8Less)
        c.admins = Array(Set(admins)).sorted(by: CJ.utf8Less)
        c.keys = keys.sorted(by: SyncGroup.keyOrder)
        return c
    }

    func descriptive() -> CJ {
        var kv: [(String, CJ)] = [("name", .str(name))]
        if let v = description { kv.append(("description", .str(v))) }
        if let v = type { kv.append(("type", .str(v))) }
        if let v = creator { kv.append(("creator", .str(v))) }
        if let v = avatarEmoji { kv.append(("avatarEmoji", .str(v))) }
        if let v = pinnedMessageId { kv.append(("pinnedMessageId", .str(v))) }
        if let v = pinnedBy { kv.append(("pinnedBy", .str(v))) }
        return .obj(kv)
    }

    func canonicalJson() -> String {
        let c = canonical()
        var kv: [(String, CJ)] = [("groupId", .str(c.groupId)), ("members", .arr(c.members.map { .str($0) })),
                                  ("admins", .arr(c.admins.map { .str($0) })), ("epoch", .int(c.epoch))]
        if let v = c.epochAtMs { kv.append(("epochAt", .str(DSTime.iso(v)))) }
        if case .obj(let d) = c.descriptive() { kv.append(contentsOf: d) }
        if let v = c.nameAtMs { kv.append(("nameAt", .str(DSTime.iso(v)))) }
        kv.append(("avatar", c.avatar?.cj ?? .null))
        if let v = c.avatarAtMs { kv.append(("avatarAt", .str(DSTime.iso(v)))) }
        kv.append(("keys", .arr(c.keys.map { $0.cj })))
        kv.append(("left", .bool(c.left)))
        if let v = c.leftAtMs { kv.append(("leftAt", .str(DSTime.iso(v)))) }
        if let v = c.joinedAtMs { kv.append(("joinedAt", .str(DSTime.iso(v)))) }
        if let v = c.muted { kv.append(("muted", .bool(v))) }
        if let v = c.mutedAtMs { kv.append(("mutedAt", .str(DSTime.iso(v)))) }
        kv.append(("updatedAt", .str(DSTime.iso(c.updatedAtMs))))
        return CJ.obj(kv).string
    }

    func json() -> DSObj {
        var o: DSObj = ["groupId": groupId, "name": name, "avatar": avatar?.json() ?? NSNull(),
                        "members": members, "admins": admins, "epoch": NSNumber(value: epoch), "left": left,
                        "keys": keys.map { $0.json() }, "updatedAt": DSTime.iso(updatedAtMs)]
        if let v = nameAtMs { o["nameAt"] = DSTime.iso(v) }
        if let v = avatarAtMs { o["avatarAt"] = DSTime.iso(v) }
        if let v = epochAtMs { o["epochAt"] = DSTime.iso(v) }
        if let v = leftAtMs { o["leftAt"] = DSTime.iso(v) }
        if let v = joinedAtMs { o["joinedAt"] = DSTime.iso(v) }
        if let v = description { o["description"] = v }
        if let v = type { o["type"] = v }
        if let v = creator { o["creator"] = v }
        if let v = avatarEmoji { o["avatarEmoji"] = v }
        if let v = pinnedMessageId { o["pinnedMessageId"] = v }
        if let v = pinnedBy { o["pinnedBy"] = v }
        if let v = muted { o["muted"] = v }
        if let v = mutedAtMs { o["mutedAt"] = DSTime.iso(v) }
        return o
    }

    static func from(_ o: DSObj) -> SyncGroup? {
        guard let gid = o["groupId"] as? String else { return nil }
        func keysOf(_ k: String) -> [String] {
            (o[k] as? [Any] ?? []).compactMap { v -> String? in
                if let s = v as? String, !s.isEmpty { return DSIds.canonicalKey(s) }
                // README variant: members as objects {publicKey, isAdmin, …}
                if let d = v as? DSObj, let s = DSJSON.str(d, "publicKey") { return DSIds.canonicalKey(s) }
                return nil
            }
        }
        var admins = keysOf("admins")
        if o["admins"] == nil {
            admins = DSJSON.objs(o, "members").filter { DSJSON.bool($0, "isAdmin") }.compactMap { DSJSON.str($0, "publicKey").map(DSIds.canonicalKey) }
        }
        var keys: [SyncGroupKey] = []
        for k in DSJSON.objs(o, "keys") {
            guard let key = DSJSON.str(k, "key") else { continue }
            keys.append(SyncGroupKey(kind: DSJSON.str(k, "kind") ?? SyncGroupKey.kindLegacyAes, version: DSJSON.int64(k, "version") ?? 0, key: key))
        }
        var avatar: SyncAvatar? = nil
        if let a = o["avatar"] as? DSObj, let sha = DSJSON.str(a, "sha256")?.lowercased() {
            avatar = SyncAvatar(sha256: sha, size: DSJSON.int64(a, "size") ?? -1, mime: DSJSON.str(a, "mime") ?? "image/jpeg")
        }
        return SyncGroup(groupId: DSIds.canonicalGroupId(gid), name: DSJSON.string(o, "name"), nameAtMs: DSJSON.isoMs(o, "nameAt"),
                         avatar: avatar, avatarAtMs: DSJSON.isoMs(o, "avatarAt"), members: keysOf("members"), admins: admins,
                         epoch: DSJSON.int64(o, "epoch") ?? 0, epochAtMs: DSJSON.isoMs(o, "epochAt"),
                         left: DSJSON.bool(o, "left"), leftAtMs: DSJSON.isoMs(o, "leftAt"), joinedAtMs: DSJSON.isoMs(o, "joinedAt"), keys: keys,
                         updatedAtMs: DSJSON.isoMs(o, "updatedAt") ?? 0, description: DSJSON.str(o, "description"),
                         type: DSJSON.str(o, "type"), creator: DSJSON.str(o, "creator").map(DSIds.canonicalKey),
                         avatarEmoji: DSJSON.str(o, "avatarEmoji"), pinnedMessageId: DSJSON.str(o, "pinnedMessageId"),
                         pinnedBy: DSJSON.str(o, "pinnedBy"),
                         muted: DSJSON.boolOrNil(o, "muted"), mutedAtMs: DSJSON.isoMs(o, "mutedAt"))
    }
}

struct SyncProfile {
    var name: String?
    var updatedAtMs: Int64
    var extra: DSObj = [:]

    func json() -> DSObj {
        var o = extra
        if let name { o["name"] = name }
        o["updatedAt"] = DSTime.iso(updatedAtMs)
        return o
    }

    static func from(_ o: DSObj) -> SyncProfile? {
        guard let at = DSJSON.isoMs(o, "updatedAt") else { return nil }
        var extra = o
        extra.removeValue(forKey: "name")
        extra.removeValue(forKey: "updatedAt")
        return SyncProfile(name: DSJSON.str(o, "name"), updatedAtMs: at, extra: extra)
    }
}

struct SyncReadState: Equatable {
    var conv: String
    var readUpToMs: Int64
    var atMs: Int64
    func json() -> DSObj { ["conv": conv, "readUpTo": DSTime.iso(readUpToMs), "at": DSTime.iso(atMs)] }
    static func from(_ o: DSObj) -> SyncReadState? {
        guard let c = o["conv"] as? String, let r = DSJSON.isoMs(o, "readUpTo"), let a = DSJSON.isoMs(o, "at") else { return nil }
        return SyncReadState(conv: c, readUpToMs: r, atMs: a)
    }
}

// MARK: Merge rules (design §8, merge.json)

enum SyncMerge {
    static let statusRank = ["failed": 0, "pending": 1, "sent": 2, "delivered": 3, "read": 4]

    static func status(_ a: String, _ b: String) -> String {
        (statusRank[a] ?? 0) >= (statusRank[b] ?? 0) ? a : b
    }

    static func editedGE(_ a: SyncEdited, _ b: SyncEdited) -> Bool {
        let ha = a.atMs != nil, hb = b.atMs != nil
        if ha != hb { return ha }
        if ha, a.atMs != b.atMs { return a.atMs! > b.atMs! }
        return CJ.utf8Compare(SyncCodec.editedCJ(a).string, SyncCodec.editedCJ(b).string) >= 0
    }

    static func edited(_ a: SyncEdited?, _ b: SyncEdited?) -> SyncEdited? {
        guard let a else { return b }
        guard let b else { return a }
        return editedGE(a, b) ? a : b
    }

    static func reactions(_ a: [String: [String]], _ b: [String: [String]]) -> [String: [String]] {
        var out: [String: Set<String>] = [:]
        for src in [a, b] { for (e, ks) in src { out[e, default: []].formUnion(ks) } }
        var r: [String: [String]] = [:]
        for (e, ks) in out where !ks.isEmpty { r[e] = ks.sorted(by: CJ.utf8Less) }
        return r
    }

    static func message(_ local: SyncMessage, _ remote: SyncMessage) -> SyncMessage {
        var m = local
        m.status = status(local.status, remote.status)
        m.read = local.read || remote.read
        if local.deleted || remote.deleted {
            m.deleted = true
            m.text = nil
            m.edited = nil
            m.reactions = [:]
            return m
        }
        m.edited = edited(local.edited, remote.edited)
        m.reactions = reactions(local.reactions, remote.reactions)
        return m
    }

    static func absent(_ v: Any?) -> Bool {
        guard let v else { return true }
        if let s = v as? String { return s.isEmpty }
        if let b = v as? Bool { return !b }
        return false
    }

    /// __BLOCK_SYNC_LWW_2026_09_24__ a local side with neither value nor timestamp also adopts a
    /// timestamped "absent" remote value (an unblock = blocked:false + blockedAt), so an OLDER block
    /// from a third device cannot re-block through the gap-fill (merge.json, Kotlin, devsync_ref.py).
    static func lwwField<T>(_ lv: T?, _ lat: Int64?, _ rv: T?, _ rat: Int64?) -> (T?, Int64?) {
        if let lat, let rat { return rat > lat ? (rv, rat) : (lv, lat) }
        if lat == nil, absent(lv), !absent(rv) || rat != nil { return (rv, rat) }
        return (lv, lat)
    }

    static func contact(_ local: SyncContact, _ remote: SyncContact) -> SyncContact {
        let (a, aAt) = lwwField(local.alias, local.aliasAtMs, remote.alias, remote.aliasAtMs)
        let (b, bAt) = lwwField(local.blocked, local.blockedAtMs, remote.blocked, remote.blockedAtMs)
        let (v, vAt) = lwwField(local.verified, local.verifiedAtMs, remote.verified, remote.verifiedAtMs)
        return SyncContact(publicKey: local.publicKey, alias: a, aliasAtMs: aAt, blocked: b, blockedAtMs: bAt, verified: v, verifiedAtMs: vAt)
    }

    static func profile(_ local: SyncProfile?, _ remote: SyncProfile?) -> SyncProfile? {
        guard let local else { return remote }
        guard let remote else { return local }
        return remote.updatedAtMs > local.updatedAtMs ? remote : local
    }

    static func readState(_ l: SyncReadState, _ r: SyncReadState) -> SyncReadState {
        SyncReadState(conv: l.conv, readUpToMs: max(l.readUpToMs, r.readUpToMs), atMs: max(l.atMs, r.atMs))
    }

    private static func lwwWins(_ aAt: Int64?, _ aCanon: String, _ bAt: Int64?, _ bCanon: String) -> Bool {
        let ha = aAt != nil, hb = bAt != nil
        if ha != hb { return ha }
        if ha, aAt != bAt { return aAt! > bAt! }
        return CJ.utf8Compare(aCanon, bCanon) >= 0
    }

    static func group(_ local: SyncGroup?, _ remote: SyncGroup) -> SyncGroup {
        guard let local else { return remote.canonical() }
        let a = local.canonical(), b = remote.canonical()
        var out = a
        if a.epoch != b.epoch {
            let w = a.epoch > b.epoch ? a : b
            out.members = w.members; out.admins = w.admins; out.epoch = w.epoch; out.epochAtMs = w.epochAtMs
        } else {
            out.members = Array(Set(a.members + b.members)).sorted(by: CJ.utf8Less)
            out.admins = Array(Set(a.admins + b.admins)).sorted(by: CJ.utf8Less)
            out.epochAtMs = [a.epochAtMs, b.epochAtMs].compactMap { $0 }.max()
        }
        let named = lwwWins(a.nameAtMs, a.descriptive().string, b.nameAtMs, b.descriptive().string) ? a : b
        out.name = named.name; out.nameAtMs = named.nameAtMs; out.description = named.description; out.type = named.type
        out.creator = named.creator; out.avatarEmoji = named.avatarEmoji; out.pinnedMessageId = named.pinnedMessageId
        out.pinnedBy = named.pinnedBy
        let av = lwwWins(a.avatarAtMs, (a.avatar?.cj ?? .null).string, b.avatarAtMs, (b.avatar?.cj ?? .null).string) ? a : b
        out.avatar = av.avatar; out.avatarAtMs = av.avatarAtMs
        var byKind: [String: SyncGroupKey] = [:]
        for k in a.keys + b.keys {
            let kv = k.kind + "\u{0}" + String(k.version)
            if let prev = byKind[kv], !CJ.utf8Less(k.key, prev.key) { continue }
            byKind[kv] = k
        }
        out.keys = byKind.values.sorted { x, y in
            x.kind != y.kind ? CJ.utf8Less(x.kind, y.kind) : x.version < y.version
        }
        // __DEVSYNC_REJOIN_2026_09_23__ C.16: joinedAt = max; a leave counts iff leftAt >= joinedAt
        // (untimestamped: only while no join is known); leftAt = the LATEST counting leave. Replaces
        // "earliest leftAt", which is not associative.
        out.joinedAtMs = [a.joinedAtMs, b.joinedAtMs].compactMap { $0 }.max()
        (out.left, out.leftAtMs) = leftState([a, b], joinedAt: out.joinedAtMs)
        // __GROUP_MUTE_SYNC_2026_09_24__ later mutedAt wins; timestamped beats untimestamped; ties by
        // canonical-JSON bytes (true > null > false). Same rule as Kotlin / devsync_ref.py.
        func muteCJ(_ v: Bool?) -> String { v.map { CJ.bool($0).string } ?? CJ.null.string }
        let mu = lwwWins(a.mutedAtMs, muteCJ(a.muted), b.mutedAtMs, muteCJ(b.muted)) ? a : b
        out.muted = mu.muted; out.mutedAtMs = mu.mutedAtMs
        out.updatedAtMs = max(a.updatedAtMs, b.updatedAtMs)
        return out
    }

    /// (left, the latest leave that counts) — port of Kotlin `SyncMerge.leftState`.
    static func leftState(_ groups: [SyncGroup], joinedAt: Int64?) -> (Bool, Int64?) {
        var left = false
        var at: Int64? = nil
        for g in groups where g.left {
            if let la = g.leftAtMs {
                if joinedAt == nil || la >= joinedAt! { left = true; at = max(at ?? la, la) }
            } else if joinedAt == nil { left = true }
        }
        return (left, at)
    }
}

// MARK: State store + linked-device registry + pending media

protocol DSStateStore: AnyObject {
    func read(_ name: String) -> String?
    func write(_ name: String, _ json: String)
}

final class DSMemoryStateStore: DSStateStore {
    private var map: [String: String] = [:]
    private let lock = NSLock()
    func read(_ name: String) -> String? { lock.lock(); defer { lock.unlock() }; return map[name] }
    func write(_ name: String, _ json: String) { lock.lock(); map[name] = json; lock.unlock() }
}

final class DSLinkedDeviceRegistry {
    static let file = "devsync-devices"
    private let state: DSStateStore
    private let selfId: String
    private var linkedMap: [String: DSLinkedDevice] = [:]
    private var order: [String] = []
    private(set) var revokedSet: [String] = []
    private let lock = NSLock()

    init(state: DSStateStore, selfId: String) {
        self.state = state
        self.selfId = selfId
        load()
    }

    private func sync<T>(_ f: () -> T) -> T { lock.lock(); defer { lock.unlock() }; return f() }

    func linked() -> [DSLinkedDevice] { sync { order.compactMap { linkedMap[$0] } } }
    func revoked() -> [String] { sync { revokedSet } }
    func get(_ id: String) -> DSLinkedDevice? { sync { linkedMap[id] } }
    func isLinked(_ id: String) -> Bool { sync { linkedMap[id] != nil } }
    func isRevoked(_ id: String) -> Bool { sync { revokedSet.contains(id) } }
    var isEmpty: Bool { sync { linkedMap.isEmpty } }

    func link(_ d: DSLinkedDevice) {
        guard d.isConsistent, d.deviceId != selfId else { return }
        sync {
            revokedSet.removeAll { $0 == d.deviceId }
            if linkedMap[d.deviceId] == nil { order.append(d.deviceId) }
            linkedMap[d.deviceId] = d
            persistLocked()
        }
    }

    func revoke(_ id: String) {
        sync {
            linkedMap.removeValue(forKey: id)
            order.removeAll { $0 == id }
            if !revokedSet.contains(id) { revokedSet.append(id) }
            persistLocked()
        }
    }

    func touch(_ id: String, name: String, platform: String, syncedAtMs: Int64) {
        sync {
            guard var d = linkedMap[id] else { return }
            if !name.trimmingCharacters(in: .whitespaces).isEmpty { d.name = name }
            d.platform = platform
            d.lastSyncAtMs = syncedAtMs
            linkedMap[id] = d
            persistLocked()
        }
    }

    /// Returns true when the list changed.
    func mergeDevicesRecord(_ r: DSDevicesRecord, from: String) -> Bool {
        sync {
            var changed = false
            for id in r.revoked where id != selfId {
                if !revokedSet.contains(id) { revokedSet.append(id); changed = true }
                if linkedMap.removeValue(forKey: id) != nil { order.removeAll { $0 == id }; changed = true }
            }
            for var e in r.linked {
                if e.deviceId == selfId || revokedSet.contains(e.deviceId) || linkedMap[e.deviceId] != nil { continue }
                if !e.isConsistent { continue }
                e.approvedBy = from
                e.lastSyncAtMs = nil
                linkedMap[e.deviceId] = e
                order.append(e.deviceId)
                changed = true
            }
            if changed { persistLocked() }
            return changed
        }
    }

    /// What we tell a linked peer: every OTHER linked device (plus self), and every revocation.
    func devicesRecordFor(_ peer: String, selfEntry: DSLinkedDevice?) -> DSDevicesRecord {
        sync {
            var list = order.compactMap { linkedMap[$0] }.filter { $0.deviceId != peer }
            if let s = selfEntry { list.append(s) }
            return DSDevicesRecord(linked: list, revoked: revokedSet)
        }
    }

    private func load() {
        guard let raw = state.read(Self.file), let o = DSJSON.obj(Data(raw.utf8)) else { return }
        for e in DSJSON.objs(o, "linked") {
            if let d = DSLinkedDevice.from(e) { if linkedMap[d.deviceId] == nil { order.append(d.deviceId) }; linkedMap[d.deviceId] = d }
        }
        revokedSet = (o["revoked"] as? [Any] ?? []).compactMap { $0 as? String }
    }

    private func persistLocked() {
        let o: DSObj = ["v": 1, "linked": order.compactMap { linkedMap[$0]?.storage() }, "revoked": revokedSet]
        state.write(Self.file, String(decoding: DSJSON.data(o), as: UTF8.self))
    }
}

/// Media referenced by synced messages whose bytes have not arrived yet (persistent, §6.2).
final class DSPendingMedia {
    static let file = "devsync-media"
    struct Ref: Hashable { let conv: String; let id: String }
    private struct Entry { var size: Int64; var ts: Int64; var refs: [Ref] }
    private var map: [String: Entry] = [:]
    private let state: DSStateStore
    private let lock = NSLock()

    init(state: DSStateStore) {
        self.state = state
        if let raw = state.read(Self.file), let o = DSJSON.obj(Data(raw.utf8)) {
            for (sha, v) in o {
                guard let e = v as? DSObj else { continue }
                let refs = DSJSON.objs(e, "refs").compactMap { r -> Ref? in
                    guard let c = r["conv"] as? String, let i = r["id"] as? String else { return nil }
                    return Ref(conv: c, id: i)
                }
                map[sha] = Entry(size: DSJSON.int64(e, "size") ?? -1, ts: DSJSON.int64(e, "ts") ?? 0, refs: refs)
            }
        }
    }

    private func sync<T>(_ f: () -> T) -> T { lock.lock(); defer { lock.unlock() }; return f() }

    func add(_ sha: String, size: Int64, conv: String, id: String, ts: Int64) {
        sync {
            var e = map[sha] ?? Entry(size: size, ts: ts, refs: [])
            let r = Ref(conv: conv, id: id)
            if !e.refs.contains(r) { e.refs.append(r) }
            if ts > e.ts { e.ts = ts }
            map[sha] = e
            persistLocked()
        }
    }

    func contains(_ sha: String) -> Bool { sync { map[sha] != nil } }
    func refs(_ sha: String) -> [Ref] { sync { map[sha]?.refs ?? [] } }
    func remove(_ sha: String) { sync { if map.removeValue(forKey: sha) != nil { persistLocked() } } }
    func shasNewestFirst() -> [String] { sync { map.sorted { $0.value.ts > $1.value.ts }.map(\.key) } }
    var count: Int { sync { map.count } }

    private func persistLocked() {
        var o: DSObj = [:]
        for (sha, e) in map {
            o[sha] = ["size": NSNumber(value: e.size), "ts": NSNumber(value: e.ts),
                      "refs": e.refs.map { ["conv": $0.conv, "id": $0.id] }]
        }
        state.write(Self.file, String(decoding: DSJSON.data(o), as: UTF8.self))
    }
}

// MARK: Store seam (implemented by the iOS adapter; in-memory for the self-test)

struct DSApplyResult {
    var inserted = 0, updated = 0, skippedNoGroup = 0, failed = 0
}

protocol DevSyncStore: AnyObject {
    /// Every conversation that has at least one syncable message.
    func conversations() -> [SyncConversation]
    func messages(_ conversationId: String) -> [SyncMessage]
    /// Same messages WITH media sha256/size filled in (for MESSAGES replies).
    func messagesByIds(_ conversationId: String, _ ids: [String]) -> [SyncMessage]
    func apply(_ conversation: SyncConversation, inserts: [SyncMessage], updates: [SyncMessage]) -> DSApplyResult
    /// Plaintext media bytes by sha256 (lowercase hex), or nil.
    func openMedia(_ sha256: String) -> Data?
    /// `messageId == DSEngine.avatarRef` = the picture of group `conversationId`.
    func attachMedia(conversationId: String, messageId: String, sha256: String, file: URL) -> Bool
    func contacts() -> [SyncContact]
    func putContact(_ c: SyncContact)
    func groups() -> [SyncGroup]
    func putGroup(_ g: SyncGroup)
    func profile() -> SyncProfile?
    func putProfile(_ p: SyncProfile)
    func readStates() -> [SyncReadState]
    func putReadState(_ r: SyncReadState)
    /// Drop any snapshot cached by the adapter (local data may have changed).
    func invalidate()
}

// MARK: Links (one session's byte pipe)

protocol DSLink: AnyObject {
    var transport: String { get }
    var label: String { get }
    func send(kind: Int, _ noise: Data)
    func close()
}

final class DSTimer {
    fileprivate var item: DispatchWorkItem?
    func cancel() { item?.cancel(); item = nil }
}

struct DSNet { var metered = false; var lowPower = false }

struct DSConfig {
    var deviceName: String
    var platform: String
    var appVersion: String
    var cacheDir: URL
    var historySource = false
    var mediaPolicy = DSHello.mediaAlways
    var network: () -> DSNet = { DSNet() }
    var windowRecords = 32
    var windowBytes: Int64 = 2 * 1024 * 1024
    var rekeyMessages = DSTransportCipher.rekeyMessages
    var rekeyBytes = DSTransportCipher.rekeyBytes
    var relaySessionCapBytes: Int64 = 1 << 30
    var sessionMaxMs: Int64 = 60 * 60 * 1000
    var relayDelayMs: Int64 = 3000
    var clock: () -> Int64 = DSTime.nowMs
    /// __DEVSYNC_LIVE_UPGRADE_2026_09_23__ near-instant mirroring (§9, Appendix C.15): a local change
    /// re-runs the diff on every open session after this quiet period (a burst is batched)…
    var liveDebounceMs: Int64 = 150
    /// …but never later than this after the FIRST change of a burst.
    var liveMaxDelayMs: Int64 = 600
    /// LAN keepalive (Appendix C.14): an idle LAN session sends a redundant ACK this often.
    var lanKeepaliveMs: Int64 = 10_000
    /// A keepalive-capable LAN peer silent for this long is dead: close, fall back to the relay.
    var lanIdleTimeoutMs: Int64 = 30_000
    /// Delay before re-dialling the relay after a LAN session died (§5.3 step 5).
    var relayFallbackDelayMs: Int64 = 250
}

struct DSPairingRequest: Equatable {
    var peerId: String
    var name: String
    var platform: String
    var code: String
    var transport: String
    var thisDeviceIsNew: Bool
    var peerApproved = false
}

struct DSSessionStats {
    var inserted = 0, updated = 0, skippedNoGroup = 0, invalid = 0
    var mediaFetched = 0
    var mediaBytes: Int64 = 0
    var recordsIn = 0, recordsOut = 0, maxUnackedObserved = 0
}

enum DSSessionState: String { case handshake, pairing, sync, closed }

struct DSSessionInfo {
    var peerId: String?
    var peerName: String?
    var transport: String
    var state: DSSessionState
    var pullComplete: Bool
    var mediaComplete: Bool
    var stats: DSSessionStats
    var closeReason: String?
}

/// Everything is called on the engine queue.
protocol DSEngineDelegate: AnyObject {
    func devsyncDevicesChanged()
    func devsyncPairingRequest(_ r: DSPairingRequest)
    func devsyncPairingResolved(peerId: String, linked: Bool)
    func devsyncSessionChanged(_ i: DSSessionInfo)
    func devsyncApplied(peerId: String, conversationId: String, result: DSApplyResult)
    func devsyncSynced(peerId: String, stats: DSSessionStats)
    func devsyncAlert(kind: String, deviceName: String)
}

// MARK: Session (design §4.1, §6) — port of DevSyncSession.kt

final class DSSession {
    unowned let engine: DSEngine
    let link: DSLink
    let initiator: Bool
    private let expectedPeerId: String?

    private(set) var state = DSSessionState.handshake
    private(set) var peerId: String?
    private(set) var peerHello: DSHello?
    private(set) var peerStatic: Data?
    private(set) var sasCode: String?
    private(set) var initiatorId: String?
    let startedAtMs: Int64
    private(set) var lastActivityMs: Int64
    private(set) var closeReason: String?

    private let hs: DSNoiseHandshake
    private var sendCipher: DSTransportCipher?
    private var recvCipher: DSTransportCipher?
    private var handshakeTimer: DSTimer?
    private var hsReadMsg1 = false

    private var nextSeq: UInt32 = 1
    private var lastRecvSeq: UInt32 = 0
    private var recvSinceAck = 0
    private var ackTimer: DSTimer?
    /// `liveBody` = the whole LIVE body, set on the LAST fragment of a LIVE record (handover, C.11).
    private struct Out { let type: Int; let flags: Int; let body: Data; var liveBody: Data? = nil }
    private var outQueue: [Out] = []
    private var outHead = 0
    private var unacked: [(UInt32, Int64)] = []
    private var unackedBytes: Int64 = 0
    /// LIVE bodies sent but not yet acknowledged, keyed by the seq of their last fragment.
    private var liveUnacked: [(Int64, Data)] = []

    // LAN liveness (Appendix C.14) — __DEVSYNC_LIVE_UPGRADE_2026_09_23__
    private var lastSentMs: Int64 = 0
    private var keepaliveTimer: DSTimer?
    /// The peer sends keepalives (it sent a redundant ACK): its silence then means the LAN is dead.
    private(set) var peerKeepsAlive = false
    private var lastAckUpTo: Int64 = -1
    private let reassembler = DSWire.Reassembler()
    private(set) var relayBytesSent: Int64 = 0

    private var locallyApproved = false
    private var peerApproved = false
    private var pairingTimer: DSTimer?

    private var pendingRequests = 0
    private var summaryReceived = false
    private(set) var pullComplete = false
    private var localIdCache: [String: [String: String]] = [:]
    private var msgCache: [String: [SyncMessage]] = [:]
    // Near-instant mirroring (§9, Appendix C.15) — __DEVSYNC_LIVE_UPGRADE_2026_09_23__
    /// conv -> newest timestamp covered by our last SUMMARY (or LIVE push) to this peer.
    private var advertisedNewest: [String: Int64] = [:]
    /// conv -> ids the peer gave us that are newer than `advertisedNewest` (never pushed back).
    private var knownToPeer: [String: Set<String>] = [:]
    private var lastSummaryFp: String?
    private var summaryDeferred = false
    private var groupsFp: String?
    private var contactsFp: String?
    private var profileFp: String?
    private var readFp: [String: String] = [:]
    var stats = DSSessionStats()

    private var mediaQueue: [String] = []
    private var mediaFailed = Set<String>()
    private var retriedOnce = Set<String>()
    private final class MediaFetch {
        let sha: String; let file: URL; var expectOffset: Int64; var chunksLeft: Int; let retried: Bool
        init(sha: String, file: URL, expectOffset: Int64, chunksLeft: Int, retried: Bool) {
            self.sha = sha; self.file = file; self.expectOffset = expectOffset; self.chunksLeft = chunksLeft; self.retried = retried
        }
    }
    private var mediaInFlight: MediaFetch?
    private(set) var mediaComplete = false
    private var closeAfterDrain: String?

    static let msg1Payload = Data("{\"v\":1}".utf8)
    static let maxWant = 256
    static let batchTarget = 48 * 1024
    static let ackEvery = 8
    static let ackDelayMs: Int64 = 250
    static let mediaChunksPerGet = 16
    /// Above this many new messages in one conversation the one-hop push yields to the diff.
    static let livePushMax = 256

    init(engine: DSEngine, link: DSLink, initiator: Bool, expectedPeerId: String?, ephemeralPriv: Data? = nil) throws {
        self.engine = engine
        self.link = link
        self.initiator = initiator
        self.expectedPeerId = expectedPeerId
        startedAtMs = engine.now()
        lastActivityMs = startedAtMs
        lastSentMs = startedAtMs
        let prologue: Data
        if link.transport == DSKeys.transportLAN {
            prologue = DSKeys.prologue(DSKeys.transportLAN)
        } else {
            guard let e = expectedPeerId, let a = DSCrypto.unhex(engine.deviceId), let b = DSCrypto.unhex(e) else {
                throw DevSyncError("relay session without a peer id")
            }
            prologue = DSKeys.prologue(link.transport, a, b)
        }
        hs = try DSNoiseHandshake(initiator: initiator, staticPriv: engine.deviceKeyPriv, psk: engine.psk,
                                  prologue: prologue, ephemeralPriv: ephemeralPriv)
    }

    // ---------------------------------------------------------------- handshake

    func start() {
        handshakeTimer = engine.schedule(DSEngine.handshakeTimeoutMs) { [weak self] in
            if self?.state == .handshake { self?.close("handshake-timeout", silent: true) }
        }
        if initiator {
            do {
                let m1 = try hs.writeMessage1(Self.msg1Payload)
                initiatorId = engine.deviceId
                link.send(kind: DSWire.kindMsg1, m1)
            } catch { close("error", silent: true) }
        }
    }

    /// kind 0 = infer from the handshake state (LAN).
    func onFrame(kind: Int, _ noise: Data) {
        if state == .closed { return }
        lastActivityMs = engine.now()
        do {
            if sendCipher == nil { try onHandshake(kind, noise) } else {
                if kind != 0 && kind != DSWire.kindTransport { throw DevSyncError("handshake message on an established session") }
                try onTransport(noise)
            }
        } catch {
            engine.log("session \(link.label): \(error)")
            close("protocol", silent: true)
        }
    }

    private func onHandshake(_ kind: Int, _ msg: Data) throws {
        if initiator {
            if kind != 0 && kind != DSWire.kindMsg2 { throw DevSyncError("unexpected handshake kind \(kind)") }
            guard let hello = DSHello.decode(try hs.readMessage2(msg)) else { throw DevSyncError("bad HELLO") }
            try verifyPeer(hs.remoteStatic!, hello)
            let m3 = try hs.writeMessage3(engine.hello().encode())
            link.send(kind: DSWire.kindMsg3, m3)
            established()
        } else if !hsReadMsg1 {
            if kind != 0 && kind != DSWire.kindMsg1 { throw DevSyncError("unexpected handshake kind \(kind)") }
            _ = try hs.readMessage1(msg)
            hsReadMsg1 = true
            link.send(kind: DSWire.kindMsg2, try hs.writeMessage2(engine.hello().encode()))
        } else {
            if kind != 0 && kind != DSWire.kindMsg3 { throw DevSyncError("unexpected handshake kind \(kind)") }
            guard let hello = DSHello.decode(try hs.readMessage3(msg)) else { throw DevSyncError("bad HELLO") }
            try verifyPeer(hs.remoteStatic!, hello)
            initiatorId = hello.deviceId
            established()
        }
    }

    private func verifyPeer(_ rs: Data, _ hello: DSHello) throws {
        let id = DSKeys.deviceIdHex(rs)
        if id != hello.deviceId { throw DevSyncError("HELLO deviceId does not match the Noise static key") }
        if let e = expectedPeerId, id != e { throw DevSyncError("routing id does not match the Noise static key") }
        if id == engine.deviceId { throw DevSyncError("connected to ourselves") }
        peerId = id
        peerHello = hello
        peerStatic = rs
        if let raw = hello.raw, let f = engine.onPeerHello { f(id, raw) }
    }

    private func established() {
        handshakeTimer?.cancel()
        let (s, r) = hs.split(rekeyMessages: engine.config.rekeyMessages, rekeyBytes: engine.config.rekeyBytes)
        sendCipher = s
        recvCipher = r
        sasCode = DSKeys.sasCode(hs.handshakeHash)
        engine.onSessionEstablished(self)
    }

    func authorize() {
        if state == .closed { return }
        guard let id = peerId else { return }
        if engine.registry.isRevoked(id) {
            sendRecord(DSWire.BYE, ["reason": "revoked"])
            close("revoked")
        } else if engine.registry.isLinked(id) {
            beginSync()
        } else {
            state = .pairing
            pairingTimer = engine.schedule(DSEngine.pairingTimeoutMs) { [weak self] in
                if self?.state == .pairing { self?.close("pairing-timeout") }
            }
            engine.onPairingNeeded(self)
        }
    }

    // ---------------------------------------------------------------- pairing (§4.3)

    func localDecision(_ allow: Bool) {
        guard state == .pairing else { return }
        if !allow {
            sendRecord(DSWire.APPROVAL, ["approved": false])
            sendRecord(DSWire.BYE, ["reason": "policy"])
            close("denied")
            return
        }
        locallyApproved = true
        sendRecord(DSWire.APPROVAL, approvalBody())
        maybeFinishPairing()
    }

    private var approvalEchoed = false

    private func approvalBody() -> DSObj {
        var body: DSObj = ["approved": true]
        // §15.3: the approver also signs the new device into the per-device mailbox (`mailbox`).
        if let f = engine.approvalExtras, let pid = peerId, let ps = peerStatic, let extra = f(pid, ps, peerHello) {
            for (k, v) in extra where k != "approved" { body[k] = v }
        }
        return body
    }

    private func onApproval(_ body: DSObj) {
        if state == .sync && DSJSON.bool(body, "approved") && !approvalEchoed {
            // We already had the peer linked but it did not have us (reinstalled / restored): it
            // just approved us and waits for OUR approval. Give it, and resend the session-start
            // records it dropped while it was still pairing.
            approvalEchoed = true
            sendRecord(DSWire.APPROVAL, approvalBody())
            sendStartRecords()
            return
        }
        guard state == .pairing else { return }
        if !DSJSON.bool(body, "approved") {
            engine.onPairingRefusedByPeer(self)
            close("refused-by-peer")
            return
        }
        peerApproved = true
        if let f = engine.onApprovalReceived, let pid = peerId { f(pid, body) }
        if !locallyApproved && engine.acceptsApprovalFromUnknown() {
            engine.linkPeer(self, approvedBy: peerId)
            localDecision(true)
            return
        }
        engine.onPeerApprovedAwaitingLocal(self)
        maybeFinishPairing()
    }

    private func maybeFinishPairing() {
        if locallyApproved && peerApproved {
            pairingTimer?.cancel()
            beginSync()
        }
    }

    // ---------------------------------------------------------------- sync (§6.2)

    private func beginSync() {
        state = .sync
        engine.store.invalidate()
        engine.onSyncStarted(self)
        sendStartRecords()
        if link.transport == DSKeys.transportLAN {
            // Appendix C.14: two ACKs in a row (= a redundant one) announce keepalive support at
            // once, so a busy session that never idles long enough for a keepalive is still watched.
            sendAck(); sendAck()
            scheduleKeepalive()
        }
    }

    // ---------------------------------------------------------------- LAN liveness (Appendix C.14)

    /// An idle LAN session re-sends its last `ACK{upTo}` every `lanKeepaliveMs` (no new record
    /// type; harmless to any receiver). A peer seen doing it is keepalive-capable: if it then goes
    /// silent for `lanIdleTimeoutMs` the TCP path is dead and the session closes (→ relay, §5.3).
    private func scheduleKeepalive() {
        keepaliveTimer?.cancel()
        let period = max(10, min(engine.config.lanKeepaliveMs, engine.config.lanIdleTimeoutMs / 3))
        keepaliveTimer = engine.schedule(period) { [weak self] in self?.keepaliveTick() }
    }

    private func keepaliveTick() {
        if state == .closed { return }
        let t = engine.now()
        if peerKeepsAlive && t - lastActivityMs >= engine.config.lanIdleTimeoutMs {
            engine.log("session \(link.label): LAN silent for \((t - lastActivityMs) / 1000)s, closing")
            close("idle", silent: true)
            return
        }
        if t - lastSentMs >= engine.config.lanKeepaliveMs - 5 { sendAck() }
        scheduleKeepalive()
    }

    /// A keepalive-capable LAN peer silent for two keepalive periods: loses to a relay session (C.11).
    func lanStale() -> Bool {
        link.transport == DSKeys.transportLAN && state != .closed && peerKeepsAlive
            && engine.now() - lastActivityMs > 2 * engine.config.lanKeepaliveMs
    }

    // ---------------------------------------------------------------- transport handover (C.11)

    /// LIVE records queued or sent without an ACK: re-sent on the surviving session when a LAN
    /// session replaces this one (or this one replaces a dead LAN). The rest is diff traffic the
    /// new session recomputes; partial media resumes from the partial file.
    func takeInFlightLive() -> [Data] {
        var out = liveUnacked.map(\.1)
        if outHead < outQueue.count { for o in outQueue[outHead...] { if let l = o.liveBody { out.append(l) } } }
        liveUnacked.removeAll()
        return out
    }

    func adoptLive(_ bodies: [Data]) {
        guard state == .sync else { return }
        for b in bodies { enqueue(DSWire.LIVE, b) }
        pump()
    }

    /// DEVICES, GROUPS, CONTACTS, PROFILE, READ_STATE, then SUMMARY (groups before any group message).
    private func sendStartRecords() {
        guard let peer = peerId else { return }
        sendRecord(DSWire.DEVICES, engine.registry.devicesRecordFor(peer, selfEntry: engine.selfEntry()).json())
        groupsFp = nil; contactsFp = nil; profileFp = nil; readFp.removeAll()
        sendChangedState()
        sendSummary()
        queuePendingMedia()
    }

    /// Re-run the diff. Default = "Sync now" (everything re-read, SUMMARY always sent). The live
    /// path (C.15) passes the changed conversations (nil = any), `deferWhilePulling`, `skipUnchanged`:
    ///  1. messages newer than what our last SUMMARY covered go out at once as `LIVE` (one hop);
    ///  2. GROUPS / CONTACTS / PROFILE / READ_STATE are re-sent only when they changed;
    ///  3. SUMMARY is re-sent unless identical, so the peer pulls edits, statuses, reactions.
    ///  While OUR pull is still running, 2-3 wait for its end.
    func resync(_ convs: [String]? = nil, deferWhilePulling: Bool = false, skipUnchanged: Bool = false) {
        guard state == .sync else { return }
        if let convs { convs.forEach { invalidate($0) } } else { invalidate(); engine.store.invalidate() }
        pushNewSinceSummary(convs)
        if deferWhilePulling && summaryReceived && !pullComplete { summaryDeferred = true; return }
        sendChangedState()
        sendSummary(skipUnchanged: skipUnchanged)
    }

    /// State records whose content changed since this session last sent them (idempotent merges).
    private func sendChangedState() {
        let groups = engine.store.groups().map { $0.json() }
        let gfp = Self.fingerprint(groups)
        if gfp != groupsFp {
            if !groups.isEmpty || groupsFp != nil { sendJsonBatches(DSWire.GROUPS, "groups", groups) }
            groupsFp = gfp
        }
        let contacts = engine.store.contacts().map { $0.json() }
        let cfp = Self.fingerprint(contacts)
        if cfp != contactsFp {
            if !contacts.isEmpty || contactsFp != nil { sendJsonBatches(DSWire.CONTACTS, "contacts", contacts) }
            contactsFp = cfp
        }
        if let p = engine.store.profile()?.json() {
            let pfp = Self.fingerprint([p])
            if pfp != profileFp { sendRecord(DSWire.PROFILE, p); profileFp = pfp }
        }
        for rs in engine.store.readStates() {
            let o = rs.json()
            let fp = Self.fingerprint([o])
            if readFp[rs.conv] != fp { sendRecord(DSWire.READ_STATE, o); readFp[rs.conv] = fp }
        }
    }

    /// Local-only change detector: sorted-keys JSON, so equal content gives an equal value.
    static func fingerprint(_ items: [DSObj]) -> String {
        let d = (try? JSONSerialization.data(withJSONObject: items, options: [.sortedKeys, .withoutEscapingSlashes])) ?? Data()
        return DSCrypto.hex(DSCrypto.sha256(d))
    }

    private func sendSummary(skipUnchanged: Bool = false) {
        var arr: [DSObj] = []
        var newest: [String: Int64] = [:]
        for c in allowedConversations() {
            let ms = msgs(c.id)
            if ms.isEmpty { continue }
            let n = ms.map(\.timestampMs).max()!
            newest[c.id] = n
            var o: DSObj = ["id": c.id, "kind": c.kind, "count": ms.count,
                            "newest": DSTime.iso(n),
                            "digest": SyncCodec.digest(ms.map { ($0.id, SyncCodec.rev($0)) })]
            if let v = c.peerPublicKey { o["peerPublicKey"] = v }
            if let v = c.groupId { o["groupId"] = v }
            if let v = c.nativeId { o["nativeId"] = v }
            arr.append(o)
        }
        let body: DSObj = ["conversations": arr]
        let fp = Self.fingerprint([body])
        for (conv, n) in newest where n >= (advertisedNewest[conv] ?? .min) {
            advertisedNewest[conv] = n
            knownToPeer.removeValue(forKey: conv)
        }
        if skipUnchanged && fp == lastSummaryFp { return }
        lastSummaryFp = fp
        sendRecord(DSWire.SUMMARY, body)
    }

    /// One-hop mirroring (C.15): messages newer than the newest this peer was told about, and that
    /// did not come FROM this peer, go out as `LIVE` now. A backlog > `livePushMax` is left to the diff.
    private func pushNewSinceSummary(_ convs: [String]?) {
        for c in allowedConversations() {
            if let convs, !convs.contains(c.id) { continue }
            let adv = advertisedNewest[c.id] ?? .min
            let known = knownToPeer[c.id]
            let fresh = msgs(c.id).filter { $0.timestampMs > adv && !(known?.contains($0.idKey) ?? false) }
            if fresh.isEmpty || fresh.count > Self.livePushMax { continue }
            sendMessages(DSWire.LIVE, c, fresh.sorted { $0.timestampMs < $1.timestampMs }, replyToWant: false)
            advertisedNewest[c.id] = fresh.map(\.timestampMs).max()!
        }
    }

    private func allowedConversations() -> [SyncConversation] {
        engine.store.conversations().filter { conversationAllowed($0.kind) }
    }

    private func conversationAllowed(_ kind: String) -> Bool {
        switch kind {
        case SyncConversation.kindDirect, SyncConversation.kindGroup: return true
        case SyncConversation.kindDesktopLocal: return engine.config.platform == "desktop" && peerHello?.platform == "desktop"
        default: return false
        }
    }

    private func msgs(_ conv: String) -> [SyncMessage] {
        if let c = msgCache[conv] { return c }
        let m = engine.store.messages(conv)
        msgCache[conv] = m
        return m
    }

    private func invalidate(_ conv: String? = nil) {
        if let conv { msgCache.removeValue(forKey: conv); localIdCache.removeValue(forKey: conv) } else {
            msgCache.removeAll(); localIdCache.removeAll()
        }
    }

    private func localRevs(_ conv: String) -> [String: String] {
        if let c = localIdCache[conv] { return c }
        var d: [String: String] = [:]
        for m in msgs(conv) { d[m.idKey] = SyncCodec.rev(m) }
        localIdCache[conv] = d
        return d
    }

    private func localBuckets(_ conv: String) -> [String: (Int, String)] {
        var byDay: [String: [SyncMessage]] = [:]
        for m in msgs(conv) { byDay[DSTime.utcDay(m.timestampMs), default: []].append(m) }
        return byDay.mapValues { ms in (ms.count, SyncCodec.digest(ms.map { ($0.id, SyncCodec.rev($0)) })) }
    }

    private func onSummary(_ body: DSObj) {
        summaryReceived = true
        pullComplete = false
        var local: [String: String] = [:]
        for c in allowedConversations() {
            let ms = msgs(c.id)
            if !ms.isEmpty { local[c.id] = SyncCodec.digest(ms.map { ($0.id, SyncCodec.rev($0)) }) }
        }
        let rows = DSJSON.objs(body, "conversations").sorted { DSJSON.string($0, "newest") > DSJSON.string($1, "newest") }
        for o in rows {
            guard let conv = SyncCodec.conversationFromJson(o), conversationAllowed(conv.kind) else { continue }
            if local[conv.id] == DSJSON.string(o, "digest") { continue }
            sendRecord(DSWire.BUCKETS_REQ, ["conv": conv.id])
            pendingRequests += 1
        }
        checkPullDone()
    }

    private func isLocalConversationAllowed(_ conv: String) -> Bool { allowedConversations().contains { $0.id == conv } }

    private func onBucketsReq(_ body: DSObj) {
        let conv = DSJSON.string(body, "conv")
        var arr: [DSObj] = []
        if isLocalConversationAllowed(conv) {
            for (day, v) in localBuckets(conv).sorted(by: { $0.key > $1.key }) {
                arr.append(["day": day, "count": v.0, "digest": v.1])
            }
        }
        sendRecord(DSWire.BUCKETS, ["conv": conv, "buckets": arr])
    }

    private func onBuckets(_ body: DSObj) {
        pendingRequests = max(0, pendingRequests - 1)
        let conv = DSJSON.string(body, "conv")
        let local = localBuckets(conv)
        for b in DSJSON.objs(body, "buckets").sorted(by: { DSJSON.string($0, "day") > DSJSON.string($1, "day") }) {
            let day = DSJSON.string(b, "day")
            if local[day]?.1 == DSJSON.string(b, "digest") { continue }
            sendRecord(DSWire.IDS_REQ, ["conv": conv, "day": day])
            pendingRequests += 1
        }
        checkPullDone()
    }

    private func onIdsReq(_ body: DSObj) {
        let conv = DSJSON.string(body, "conv")
        let day = DSJSON.string(body, "day")
        var items: [[String]] = []
        if isLocalConversationAllowed(conv) {
            for m in msgs(conv).filter({ DSTime.utcDay($0.timestampMs) == day }).sorted(by: { CJ.utf8Less($0.idKey, $1.idKey) }) {
                items.append([m.id, SyncCodec.rev(m)])
            }
        }
        sendRecord(DSWire.IDS, ["conv": conv, "day": day, "items": items])
    }

    private func onIds(_ body: DSObj) {
        pendingRequests = max(0, pendingRequests - 1)
        let conv = DSJSON.string(body, "conv")
        let have = localRevs(conv)
        var want: [String] = []
        for p in (body["items"] as? [Any] ?? []) {
            guard let pair = p as? [Any], let id = pair.first as? String, !id.isEmpty else { continue }
            let rev = pair.count > 1 ? (pair[1] as? String ?? "") : ""
            let mine = have[DSIds.asciiLower(id)]
            if mine == nil || mine != rev { want.append(id) }
        }
        var i = 0
        while i < want.count {
            let chunk = Array(want[i..<min(i + Self.maxWant, want.count)])
            sendRecord(DSWire.WANT, ["conv": conv, "ids": chunk])
            pendingRequests += 1
            i += Self.maxWant
        }
        checkPullDone()
    }

    private func onWant(_ body: DSObj) {
        let conv = DSJSON.string(body, "conv")
        let ids = (body["ids"] as? [Any] ?? []).prefix(Self.maxWant).compactMap { $0 as? String }.filter { !$0.isEmpty }
        let allowed = allowedConversations()
        let targets = conv.isEmpty ? allowed : allowed.filter { $0.id == conv }
        let found = targets.map { ($0, engine.store.messagesByIds($0.id, ids)) }.filter { !$0.1.isEmpty }
        if found.isEmpty {
            sendMessages(DSWire.MESSAGES, targets.first, [], replyToWant: true)
            return
        }
        for (i, f) in found.enumerated() {
            sendMessages(DSWire.MESSAGES, f.0, f.1, replyToWant: true, moreAfter: i < found.count - 1)
        }
    }

    private func sendMessages(_ type: Int, _ conv: SyncConversation?, _ ms: [SyncMessage], replyToWant: Bool, moreAfter: Bool = false) {
        guard let conv, !ms.isEmpty else {
            if replyToWant { sendRecord(type, SyncCodec.encodeBatch(conv.map { [$0] } ?? [], [])) }
            return
        }
        var batches: [[SyncMessage]] = []
        var cur: [SyncMessage] = []
        var curBytes = 0
        for m in ms {
            let size = DSJSON.data(SyncCodec.messageToJson(m)).count
            if !cur.isEmpty && curBytes + size > Self.batchTarget { batches.append(cur); cur = []; curBytes = 0 }
            cur.append(m)
            curBytes += size
        }
        if !cur.isEmpty { batches.append(cur) }
        for (i, b) in batches.enumerated() {
            var o = SyncCodec.encodeBatch([conv], b)
            if replyToWant && (i < batches.count - 1 || moreAfter) { o["more"] = true }
            sendRecord(type, o)
        }
    }

    private func onMessages(_ body: DSObj, live: Bool) {
        applyBatch(body)
        if !live && !DSJSON.bool(body, "more") {
            pendingRequests = max(0, pendingRequests - 1)
            checkPullDone()
        } else if live {
            pumpMedia()
        }
    }

    private func applyBatch(_ body: DSObj) {
        let batch = SyncCodec.decodeBatch(body)
        stats.invalid += batch.invalid
        var byConv: [String: [SyncMessage]] = [:]
        var order: [String] = []
        for m in batch.messages {
            if byConv[m.conversation] == nil { order.append(m.conversation) }
            byConv[m.conversation, default: []].append(m)
        }
        for convId in order {
            guard let ms = byConv[convId], let conv = batch.conversations[convId], conversationAllowed(conv.kind) else { continue }
            var localById: [String: SyncMessage] = [:]
            for l in engine.store.messagesByIds(convId, ms.map(\.id)) { localById[l.idKey] = l }
            var inserts: [SyncMessage] = []
            var updates: [SyncMessage] = []
            let adv = advertisedNewest[convId] ?? .min
            for m in ms where m.timestampMs > adv { knownToPeer[convId, default: []].insert(m.idKey) }
            for m in ms {
                if let local = localById[m.idKey] {
                    let merged = SyncMerge.message(local, m)
                    if SyncCodec.rev(merged) != SyncCodec.rev(local) { updates.append(merged) }
                } else { inserts.append(m) }
            }
            if inserts.isEmpty && updates.isEmpty { continue }
            let r = engine.store.apply(conv, inserts: inserts, updates: updates)
            stats.inserted += r.inserted
            stats.updated += r.updated
            stats.skippedNoGroup += r.skippedNoGroup
            invalidate(convId)
            if r.skippedNoGroup == 0 {
                for m in inserts {
                    guard let md = m.media, let sha = md.sha256, md.omitted == nil, !m.viewOnce, !m.deleted else { continue }
                    engine.pendingMedia.add(sha, size: md.size ?? -1, conv: convId, id: m.id, ts: m.timestampMs)
                }
            }
            engine.onApplied(self, conv, r)
        }
        queuePendingMedia()
    }

    private func checkPullDone() {
        guard summaryReceived, pendingRequests <= 0, !pullComplete else { return }
        pullComplete = true
        engine.onPullComplete(self)
        pumpMedia()
        if summaryDeferred && state == .sync {
            summaryDeferred = false
            invalidate()
            engine.store.invalidate()
            sendChangedState()
            sendSummary(skipUnchanged: true)
        }
    }

    // ---------------------------------------------------------------- groups / contacts / profile / read

    private func onGroups(_ body: DSObj) {
        var local: [String: SyncGroup] = [:]
        for g in engine.store.groups() { local[g.groupId] = g }
        for o in DSJSON.objs(body, "groups") {
            guard let g = SyncGroup.from(o) else { continue }
            let mine = local[g.groupId]
            let merged = SyncMerge.group(mine, g)
            if mine == nil || merged.canonicalJson() != mine!.canonical().canonicalJson() { engine.store.putGroup(merged) }
            if let av = merged.avatar, av.sha256 != mine?.avatar?.sha256, engine.store.openMedia(av.sha256) == nil {
                engine.pendingMedia.add(av.sha256, size: av.size, conv: merged.conversationId, id: DSEngine.avatarRef, ts: merged.updatedAtMs)
            }
        }
        invalidate()
        // __DEVSYNC_MEDIA_NOW_2026_09_23__ C.15 (a): a picture whose reference arrives mid-session is
        // requested on THIS session now (the queue used to be refilled only at start / after a batch).
        queueNewPendingMedia()
    }

    private func onContacts(_ body: DSObj) {
        var local: [String: SyncContact] = [:]
        for c in engine.store.contacts() { local[c.publicKey] = c }
        for o in DSJSON.objs(body, "contacts") {
            guard let c = SyncContact.from(o) else { continue }
            let mine = local[c.publicKey]
            let merged = SyncMerge.contact(mine ?? SyncContact(publicKey: c.publicKey), c)
            if merged != mine && (mine != nil || merged != SyncContact(publicKey: c.publicKey)) { engine.store.putContact(merged) }
        }
    }

    private func onProfile(_ body: DSObj) {
        guard let p = SyncProfile.from(body) else { return }
        let mine = engine.store.profile()
        if let mine, p.updatedAtMs <= mine.updatedAtMs { return }
        engine.store.putProfile(p)
        // __DEVSYNC_PROFILE_2026_09_23__ PROFILE.photo travels by reference: fetch it with MEDIA_GET
        // (adopted byte for byte by the store), on this session now (C.15 follow-up a).
        if let ph = p.extra["photo"] as? DSObj, let sha = DSJSON.str(ph, "sha256")?.lowercased(),
           sha.count == 64, engine.store.openMedia(sha) == nil {
            engine.pendingMedia.add(sha, size: DSJSON.int64(ph, "size") ?? -1, conv: DSEngine.profileConv, id: DSEngine.profileRef, ts: p.updatedAtMs)
        }
        queueNewPendingMedia()
    }

    private func onReadState(_ body: DSObj) {
        guard let r = SyncReadState.from(body) else { return }
        let mine = engine.store.readStates().first { $0.conv == r.conv }
        let merged = mine.map { SyncMerge.readState($0, r) } ?? r
        if merged != mine { engine.store.putReadState(merged) }
    }

    private func onDevices(_ body: DSObj) {
        guard state == .sync, let pid = peerId else { return }
        if engine.registry.mergeDevicesRecord(DSDevicesRecord.from(body), from: pid) { engine.onDevicesChanged() }
    }

    // ---------------------------------------------------------------- media

    private func mediaAllowed() -> Bool { engine.mediaAllowed(with: peerHello, transport: link.transport) }

    private func queuePendingMedia() {
        guard state == .sync else { return }
        for sha in engine.pendingMedia.shasNewestFirst() {
            if !mediaQueue.contains(sha) && !mediaFailed.contains(sha) && mediaInFlight?.sha != sha { mediaQueue.append(sha) }
        }
        mediaComplete = false
        if pullComplete { pumpMedia() }
    }

    /// `queuePendingMedia` only when something new is pending (no spurious onMediaComplete).
    private func queueNewPendingMedia() {
        guard state == .sync else { return }
        let fresh = engine.pendingMedia.shasNewestFirst().contains { !mediaQueue.contains($0) && !mediaFailed.contains($0) && mediaInFlight?.sha != $0 }
        if fresh { queuePendingMedia() }
    }

    private func pumpMedia() {
        guard state == .sync, mediaInFlight == nil else { return }
        if !mediaAllowed() { mediaQueue.removeAll(); finishMedia(); return }
        while true {
            guard !mediaQueue.isEmpty else { finishMedia(); return }
            let sha = mediaQueue.removeFirst()
            if !engine.pendingMedia.contains(sha) { continue }
            let file = engine.partialFile(sha)
            let offset = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber)?.int64Value ?? 0
            mediaInFlight = MediaFetch(sha: sha, file: file, expectOffset: offset, chunksLeft: Self.mediaChunksPerGet, retried: retriedOnce.contains(sha))
            sendRecord(DSWire.MEDIA_GET, ["sha256": sha, "offset": NSNumber(value: offset), "count": Self.mediaChunksPerGet])
            return
        }
    }

    private func finishMedia() {
        if !mediaComplete {
            mediaComplete = true
            engine.onMediaComplete(self)
        }
    }

    private func onMediaGet(_ body: DSObj) {
        let sha = DSJSON.string(body, "sha256").lowercased()
        let offset = DSJSON.int64(body, "offset") ?? 0
        let count = max(1, min(64, Int(DSJSON.int64(body, "count") ?? Int64(Self.mediaChunksPerGet))))
        guard engine.mediaAllowed(with: peerHello, transport: link.transport) else { return mediaErr(sha, "policy") }
        guard let src = engine.openMediaCached(sha) else { return mediaErr(sha, "notfound") }
        let size = Int64(src.count)
        guard offset >= 0, offset <= size, let shaBytes = DSCrypto.unhex(sha), shaBytes.count == 32 else { return mediaErr(sha, "notfound") }
        var pos = offset
        if size == 0 || pos == size {
            sendBinary(DSWire.MEDIA_CHUNK, DSWire.encodeMediaChunk(sha256: shaBytes, offset: pos, last: true, data: Data()))
            return
        }
        var sent = 0
        while sent < count && pos < size {
            let n = Int(min(Int64(DSWire.mediaChunkData), size - pos))
            let start = src.startIndex + Int(pos)
            let chunk = Data(src[start..<(start + n)])
            let last = pos + Int64(n) >= size
            sendBinary(DSWire.MEDIA_CHUNK, DSWire.encodeMediaChunk(sha256: shaBytes, offset: pos, last: last, data: chunk))
            pos += Int64(n)
            sent += 1
        }
    }

    private func mediaErr(_ sha: String, _ reason: String) {
        sendRecord(DSWire.MEDIA_ERR, ["sha256": sha, "reason": reason])
    }

    private func onMediaChunk(_ body: Data) throws {
        let c = try DSWire.decodeMediaChunk(body)
        guard let f = mediaInFlight, DSCrypto.hex(c.sha256) == f.sha else { return }
        if c.offset != f.expectOffset {
            mediaInFlight = nil
            mediaQueue.insert(f.sha, at: 0)
            pumpMedia()
            return
        }
        let fm = FileManager.default
        try? fm.createDirectory(at: f.file.deletingLastPathComponent(), withIntermediateDirectories: true)
        if !fm.fileExists(atPath: f.file.path) { fm.createFile(atPath: f.file.path, contents: nil) }
        let h = try FileHandle(forWritingTo: f.file)
        try h.truncate(atOffset: UInt64(c.offset))
        try h.seek(toOffset: UInt64(c.offset))
        h.write(c.data)
        try h.close()
        f.expectOffset += Int64(c.data.count)
        stats.mediaBytes += Int64(c.data.count)
        f.chunksLeft -= 1
        if c.last {
            completeMedia(f)
        } else if f.chunksLeft <= 0 {
            f.chunksLeft = Self.mediaChunksPerGet
            sendRecord(DSWire.MEDIA_GET, ["sha256": f.sha, "offset": NSNumber(value: f.expectOffset), "count": Self.mediaChunksPerGet])
        }
    }

    private func completeMedia(_ f: MediaFetch) {
        mediaInFlight = nil
        let bytes = (try? Data(contentsOf: f.file)) ?? Data()
        let actual = DSCrypto.hex(DSCrypto.sha256(bytes))
        if actual != f.sha {
            try? FileManager.default.removeItem(at: f.file)
            if !f.retried { mediaQueue.insert(f.sha, at: 0); retriedOnce.insert(f.sha) } else { mediaFailed.insert(f.sha) }
            pumpMedia()
            return
        }
        for ref in engine.pendingMedia.refs(f.sha) {
            _ = engine.store.attachMedia(conversationId: ref.conv, messageId: ref.id, sha256: f.sha, file: f.file)
        }
        engine.pendingMedia.remove(f.sha)
        try? FileManager.default.removeItem(at: f.file)
        stats.mediaFetched += 1
        engine.onMediaFetched(self, f.sha)
        pumpMedia()
    }

    private func onMediaErr(_ body: DSObj) {
        let sha = DSJSON.string(body, "sha256")
        guard let f = mediaInFlight, f.sha == sha else { return }
        mediaInFlight = nil
        mediaFailed.insert(sha)
        if DSJSON.string(body, "reason") == "viewOnce" { engine.pendingMedia.remove(sha) }
        pumpMedia()
    }

    // ---------------------------------------------------------------- LIVE (§9)

    func sendLive(_ conv: SyncConversation, _ ms: [SyncMessage]) {
        guard state == .sync, conversationAllowed(conv.kind) else { return }
        invalidate(conv.id)
        sendMessages(DSWire.LIVE, conv, ms, replyToWant: false)
    }

    // ---------------------------------------------------------------- records in

    private func onTransport(_ noise: Data) throws {
        guard let rc = recvCipher else { return }
        let plain = try rc.decrypt(noise)
        let r = try DSWire.decodeRecord(plain)
        if DSWire.isControl(r.type) {
            if r.seq != 0 { throw DevSyncError("control record with a sequence number") }
        } else {
            if r.seq != lastRecvSeq &+ 1 { throw DevSyncError("record out of sequence") }
            lastRecvSeq = r.seq
            stats.recordsIn += 1
            recvSinceAck += 1
            if recvSinceAck >= Self.ackEvery { sendAck() } else if ackTimer == nil {
                ackTimer = engine.schedule(Self.ackDelayMs) { [weak self] in
                    guard let self else { return }
                    self.ackTimer = nil
                    if self.recvSinceAck > 0 { self.sendAck() }
                }
            }
        }
        guard let body = try reassembler.push(r) else { return }
        try dispatch(r.type, body)
    }

    private func json(_ body: Data) throws -> DSObj {
        guard let o = DSJSON.obj(body) else { throw DevSyncError("record body is not JSON") }
        return o
    }

    private func dispatch(_ type: Int, _ body: Data) throws {
        switch type {
        case DSWire.ACK: onAck(try json(body))
        case DSWire.BYE:
            let reason = DSJSON.string(try json(body), "reason", "done")
            if reason == "revoked" && state == .sync { engine.onRevokedByPeer(self) }
            close("bye:\(reason)", silent: true)
        case DSWire.APPROVAL: onApproval(try json(body))
        default:
            // Everything else requires a completed pairing. One exception: DEVICES is only ever sent
            // by a peer that already has US linked, so while pairing it counts as that peer's
            // approval (a device that lost its list re-links without a prompt on the trusting side).
            if state == .pairing && type == DSWire.DEVICES {
                onApproval(["approved": true])
                return
            }
            guard state == .sync else { return }
            switch type {
            case DSWire.DEVICES: onDevices(try json(body))
            case DSWire.SUMMARY_REQ: sendSummary()
            case DSWire.SUMMARY: onSummary(try json(body))
            case DSWire.BUCKETS_REQ: onBucketsReq(try json(body))
            case DSWire.BUCKETS: onBuckets(try json(body))
            case DSWire.IDS_REQ: onIdsReq(try json(body))
            case DSWire.IDS: onIds(try json(body))
            case DSWire.WANT: onWant(try json(body))
            case DSWire.MESSAGES: onMessages(try json(body), live: false)
            case DSWire.LIVE: onMessages(try json(body), live: true)
            case DSWire.CONTACTS: onContacts(try json(body))
            case DSWire.GROUPS: onGroups(try json(body))
            case DSWire.PROFILE: onProfile(try json(body))
            case DSWire.READ_STATE: onReadState(try json(body))
            case DSWire.MEDIA_GET: onMediaGet(try json(body))
            case DSWire.MEDIA_CHUNK: try onMediaChunk(body)
            case DSWire.MEDIA_ERR: onMediaErr(try json(body))
            default: break // unknown record types are ignored (forward compatibility)
            }
        }
    }

    // ---------------------------------------------------------------- records out

    private func sendJsonBatches(_ type: Int, _ key: String, _ items: [DSObj]) {
        var arr: [DSObj] = []
        var size = 0
        for o in items {
            let s = DSJSON.data(o).count
            if !arr.isEmpty && size + s > Self.batchTarget { sendRecord(type, [key: arr]); arr = []; size = 0 }
            arr.append(o)
            size += s
        }
        if !arr.isEmpty { sendRecord(type, [key: arr]) }
    }

    func sendRecord(_ type: Int, _ body: DSObj) { sendBinary(type, DSJSON.data(body)) }

    private func sendBinary(_ type: Int, _ body: Data) {
        guard state != .closed, sendCipher != nil else { return }
        if DSWire.isControl(type) {
            transmit(DSWire.encodeRecord(type: type, flags: 0, seq: 0, body: body))
            return
        }
        enqueue(type, body)
        pump()
    }

    private func enqueue(_ type: Int, _ body: Data) {
        let parts = DSWire.fragment(body)
        for (i, (flags, part)) in parts.enumerated() {
            outQueue.append(Out(type: type, flags: flags, body: part,
                                liveBody: type == DSWire.LIVE && i == parts.count - 1 ? body : nil))
        }
    }

    private func pump() {
        while outHead < outQueue.count && state != .closed {
            let next = outQueue[outHead]
            let size = Int64(DSWire.recordHeader + next.body.count)
            if unacked.count >= engine.config.windowRecords || (!unacked.isEmpty && unackedBytes + size > engine.config.windowBytes) { return }
            outHead += 1
            let seq = nextSeq
            nextSeq &+= 1
            unacked.append((seq, size))
            unackedBytes += size
            stats.recordsOut += 1
            stats.maxUnackedObserved = max(stats.maxUnackedObserved, unacked.count)
            if let l = next.liveBody { liveUnacked.append((Int64(seq), l)) }
            transmit(DSWire.encodeRecord(type: next.type, flags: next.flags, seq: seq, body: next.body))
        }
        if outHead >= outQueue.count {
            outQueue.removeAll(keepingCapacity: true)
            outHead = 0
            onOutboundDrained()
        } else if outHead > 256 {
            outQueue.removeFirst(outHead)
            outHead = 0
        }
    }

    private func transmit(_ record: Data) {
        guard let sc = sendCipher else { return }
        lastSentMs = engine.now()
        guard let ct = try? sc.encrypt(record) else { close("error", silent: true); return }
        if link.transport == DSKeys.transportRelay {
            relayBytesSent += Int64(ct.count)
            if relayBytesSent > engine.config.relaySessionCapBytes && state == .sync {
                link.send(kind: DSWire.kindTransport, ct)
                if let bye = try? sc.encrypt(DSWire.encodeRecord(type: DSWire.BYE, flags: 0, seq: 0, body: DSJSON.data(["reason": "policy"]))) {
                    link.send(kind: DSWire.kindTransport, bye)
                }
                close("relay-cap", silent: true)
                return
            }
        }
        link.send(kind: DSWire.kindTransport, ct)
    }

    private func sendAck() {
        ackTimer?.cancel()
        ackTimer = nil
        recvSinceAck = 0
        sendBinary(DSWire.ACK, DSJSON.data(["upTo": NSNumber(value: lastRecvSeq)]))
    }

    private func onAck(_ body: DSObj) {
        let upTo = DSJSON.int64(body, "upTo") ?? 0
        // A normal ACK always advances; the same value twice is a keepalive (Appendix C.14).
        // Duplicate cumulative ACKs are harmless below.
        if upTo == lastAckUpTo || (lastAckUpTo < 0 && upTo == 0) { peerKeepsAlive = true }
        lastAckUpTo = upTo
        while let first = unacked.first, Int64(first.0) <= upTo {
            unackedBytes -= first.1
            unacked.removeFirst()
        }
        while let f = liveUnacked.first, f.0 <= upTo { liveUnacked.removeFirst() }
        pump()
    }

    private func onOutboundDrained() {
        guard let reason = closeAfterDrain, unacked.isEmpty else { return }
        closeAfterDrain = nil
        sendRecord(DSWire.BYE, ["reason": reason])
        close(reason)
    }

    func closeGracefully(_ reason: String) {
        if state == .closed { return }
        if sendCipher == nil || state != .sync { close(reason); return }
        closeAfterDrain = reason
        if outHead >= outQueue.count && unacked.isEmpty { onOutboundDrained() }
    }

    var outboundIdle: Bool { outHead >= outQueue.count && unacked.isEmpty }

    func close(_ reason: String, silent: Bool = false) {
        if state == .closed { return }
        state = .closed
        closeReason = reason
        handshakeTimer?.cancel()
        ackTimer?.cancel()
        pairingTimer?.cancel()
        keepaliveTimer?.cancel()
        // Keep the queued LIVE bodies for a handover (`takeInFlightLive` after a BYE{lan}).
        if outHead < outQueue.count { for o in outQueue[outHead...] { if let l = o.liveBody { liveUnacked.append((.max, l)) } } }
        outQueue.removeAll()
        outHead = 0
        link.close()
        engine.onSessionClosed(self, reason)
    }

    var info: DSSessionInfo {
        DSSessionInfo(peerId: peerId, peerName: peerHello?.name, transport: link.transport, state: state,
                      pullComplete: pullComplete, mediaComplete: mediaComplete, stats: stats, closeReason: closeReason)
    }
}

// MARK: Engine — port of DevSyncEngine.kt (one serial queue owns every session + store call)

final class DSEngine {
    static let handshakeTimeoutMs: Int64 = 20_000
    static let pairingTimeoutMs: Int64 = 5 * 60_000
    static let denyQuietMs: Int64 = 60 * 60_000
    static let alertQuietMs: Int64 = 60 * 60_000
    static let sweepMs: Int64 = 30_000
    static let avatarRef = "#avatar"
    /// Media ref of the account's own profile picture (`PROFILE.photo`), outside any conversation
    /// (same values as Android `AndroidDevSyncStore.PROFILE_CONV` / `PROFILE_REF`).
    static let profileConv = "profile", profileRef = "#photo"
    /// LIVE bodies of a session the peer retired (BYE{lan}) are held this long for its LAN successor.
    static let handoverTtlMs: Int64 = 15_000
    /// Close reasons after which a LAN session is NOT replaced by a relay session (§5.3 step 5).
    static let noFallback: Set<String> = ["stopped", "revoked", "bye:revoked", "denied", "denied-recently", "refused-by-peer",
                                          "pairing-timeout", "duplicate", "bye:duplicate"]

    let config: DSConfig
    let psk: Data
    let disc: Data
    let deviceKeyPriv: Data
    let deviceKeyPub: Data
    let deviceId: String
    let registry: DSLinkedDeviceRegistry
    let pendingMedia: DSPendingMedia
    let store: DevSyncStore
    let queue: DispatchQueue
    private let queueKey = DispatchSpecificKey<Bool>()
    weak var delegate: DSEngineDelegate?
    var logger: (String) -> Void = { _ in }

    /// Extra fields merged into our APPROVAL{approved:true} (design §15.3 `mailbox`).
    var approvalExtras: ((String, Data, DSHello?) -> DSObj?)?
    /// Called with the full APPROVAL{approved:true,…} body a peer sent us.
    var onApprovalReceived: ((String, DSObj) -> Void)?

    private(set) var sessions: [DSSession] = []
    private var activeByPeer: [String: DSSession] = [:]
    private var pending: [String: DSPairingRequest] = [:]
    private var deniedUntil: [String: Int64] = [:]
    private var lastAlertAt: [String: Int64] = [:]
    private(set) var running = false
    private var sweeper: DSTimer?
    private var mediaCache: (String, Data)?

    /// Transports register maintenance hooks (hourly tag rotation, relay re-dial).
    var maintainHooks: [() -> Void] = []
    var sessionClosedHooks: [(DSSession) -> Void] = []
    /// __DEVSYNC_LIVE_UPGRADE_2026_09_23__ §5.3 step 4: re-dial known LAN endpoints while a peer is
    /// only reachable through the relay (set by the LAN transport).
    var lanUpgradeProbe: (() -> Void)?
    /// §5.3 step 5: a LAN session with this linked peer died — dial it through the relay soon
    /// (set by the relay hub; no-op for the larger id).
    var relayDialSoon: ((String) -> Void)?
    /// Called when a dialled LAN session identified its peer (endpoint memory for the upgrade probe).
    var onLanDialIdentified: ((_ label: String, _ peer: String) -> Void)?
    private var handover: [String: (Int64, [Data])] = [:]
    private var liveTimer: DSTimer?
    private var liveFirstDirtyMs: Int64 = 0
    private var liveDirtyAll = false
    private var liveDirtyConvs = Set<String>()

    init(config: DSConfig, accountPriv: Data, deviceKeyPriv: Data, store: DevSyncStore, state: DSStateStore, label: String = "oshi.devsync") throws {
        guard accountPriv.count == 32 else { throw DevSyncError("account key must be 32 bytes") }
        guard deviceKeyPriv.count == 32 else { throw DevSyncError("device key must be 32 bytes") }
        self.config = config
        psk = DSKeys.psk(accountPriv)
        disc = DSKeys.disc(accountPriv)
        self.deviceKeyPriv = deviceKeyPriv
        deviceKeyPub = try DSCrypto.x25519Public(deviceKeyPriv)
        deviceId = DSKeys.deviceIdHex(deviceKeyPub)
        registry = DSLinkedDeviceRegistry(state: state, selfId: deviceId)
        pendingMedia = DSPendingMedia(state: state)
        self.store = store
        queue = DispatchQueue(label: label, qos: .utility)
        queue.setSpecific(key: queueKey, value: true)
    }

    func now() -> Int64 { config.clock() }
    func log(_ s: String) { logger("[devsync] \(s)") }

    var isOnQueue: Bool { DispatchQueue.getSpecific(key: queueKey) == true }

    func post(_ block: @escaping () -> Void) { queue.async(execute: block) }

    /// Run on the engine queue and wait (never call from a thread the store hops to).
    func call<T>(_ block: () -> T) -> T { isOnQueue ? block() : queue.sync(execute: block) }

    @discardableResult
    func schedule(_ delayMs: Int64, _ block: @escaping () -> Void) -> DSTimer {
        let t = DSTimer()
        let item = DispatchWorkItem { [weak self] in
            guard self?.running == true else { return }
            block()
        }
        t.item = item
        queue.asyncAfter(deadline: .now() + .milliseconds(Int(delayMs)), execute: item)
        return t
    }

    func start() {
        post {
            guard !self.running else { return }
            self.running = true
            self.scheduleSweep()
        }
    }

    private func scheduleSweep() {
        sweeper = schedule(Self.sweepMs) { [weak self] in
            self?.sweep()
            self?.scheduleSweep()
        }
    }

    /// Close every session (BYE on synced ones) and stop timers. The engine can be started again.
    func stop(completion: (() -> Void)? = nil) {
        post {
            self.sweeper?.cancel()
            for s in self.sessions {
                if s.state == .sync { s.sendRecord(DSWire.BYE, ["reason": "done"]) }
                s.close("stopped", silent: true)
            }
            self.running = false
            completion?()
        }
    }

    func hello() -> DSHello {
        let net = config.network()
        return DSHello(deviceId: deviceId, name: config.deviceName, platform: config.platform, app: config.appVersion,
                       historySource: config.historySource, metered: net.metered, lowPower: net.lowPower,
                       media: config.mediaPolicy, extra: helloExtras?())
    }
    /// Extra fields for OUR HELLO (e.g. `{"dsk": …}`, design §15.3).
    var helloExtras: (() -> DSObj?)?
    /// A peer's full HELLO once the handshake proved its deviceId (before any approval).
    var onPeerHello: ((String, DSObj) -> Void)?

    func selfEntry() -> DSLinkedDevice {
        DSLinkedDevice(deviceId: deviceId, dk: DSCrypto.hex(deviceKeyPub), name: config.deviceName,
                       platform: config.platform, approvedAtMs: now())
    }

    func pendingApprovals() -> [DSPairingRequest] { call { Array(pending.values) } }
    func sessionsInfo() -> [DSSessionInfo] { call { sessions.map(\.info) } }

    func approve(_ peerId: String, allow: Bool) {
        post {
            guard let s = self.activeByPeer[peerId], s.state == .pairing else { return }
            self.pending.removeValue(forKey: peerId)
            if allow {
                self.linkPeer(s, approvedBy: self.deviceId)
                s.localDecision(true)
            } else {
                self.deniedUntil[peerId] = self.now() + Self.denyQuietMs
                s.localDecision(false)
                self.delegate?.devsyncPairingResolved(peerId: peerId, linked: false)
            }
        }
    }

    func revoke(_ peerId: String) {
        post {
            self.registry.revoke(peerId)
            if let s = self.activeByPeer[peerId] {
                s.sendRecord(DSWire.BYE, ["reason": "revoked"])
                s.close("revoked")
            }
            for s in self.activeByPeer.values where s.state == .sync {
                s.sendRecord(DSWire.DEVICES, self.registry.devicesRecordFor(s.peerId!, selfEntry: self.selfEntry()).json())
            }
            self.onDevicesChanged()
        }
    }

    func syncNow() {
        post {
            for s in self.activeByPeer.values { s.resync() }
            self.maintainHooks.forEach { $0() }
            self.lanUpgradeProbe?()
        }
    }

    func onLocalMessages(_ conv: SyncConversation, _ ms: [SyncMessage]) {
        guard !ms.isEmpty else { return }
        post { for s in self.activeByPeer.values { s.sendLive(conv, ms) } }
    }

    /// Local messages / groups / contacts / profile / read state changed (Appendix C.15): mirrored to
    /// open sessions after `liveDebounceMs` of quiet, at most `liveMaxDelayMs` after the first change
    /// of a burst. Cheap and idempotent: call it from every write path (nil = anything may have changed).
    func onLocalStateChanged() { onLocalChanged(nil) }

    /// Same, narrowed to the sync conversation ids that changed (`SyncConversation.id`).
    func onLocalChanged(_ conversationIds: [String]?) { post { self.markDirty(conversationIds) } }

    private func markDirty(_ ids: [String]?) {
        if let ids { liveDirtyConvs.formUnion(ids) } else { liveDirtyAll = true }
        let t = DSTime.monotonicMs()
        if liveTimer == nil { liveFirstDirtyMs = t }
        liveTimer?.cancel()
        let left = config.liveMaxDelayMs - (t - liveFirstDirtyMs)
        liveTimer = schedule(min(config.liveDebounceMs, max(0, left))) { [weak self] in self?.flushLive() }
    }

    private func flushLive() {
        liveTimer = nil
        let convs: [String]? = liveDirtyAll ? nil : Array(liveDirtyConvs)
        liveDirtyAll = false
        liveDirtyConvs.removeAll()
        store.invalidate()
        for s in Array(activeByPeer.values) { s.resync(convs, deferWhilePulling: true, skipUnchanged: true) }
    }

    func disconnectAll(_ reason: String = "dropped") { post { for s in self.sessions { s.close(reason, silent: true) } } }

    // ---------------------------------------------------------------- session plumbing

    @discardableResult
    func newSession(_ link: DSLink, initiator: Bool, expectedPeerId: String?, ephemeralPriv: Data? = nil) -> DSSession? {
        guard let s = try? DSSession(engine: self, link: link, initiator: initiator, expectedPeerId: expectedPeerId, ephemeralPriv: ephemeralPriv) else {
            link.close()
            return nil
        }
        sessions.append(s)
        s.start()
        return s
    }

    func activeSession(_ peer: String) -> DSSession? {
        guard let s = activeByPeer[peer], s.state != .closed else { return nil }
        return s
    }

    func onSessionEstablished(_ s: DSSession) {
        guard let peer = s.peerId else { return }
        if s.initiator && s.link.transport == DSKeys.transportLAN { onLanDialIdentified?(s.link.label, peer) }
        // __DEVSYNC_LIVE_UPGRADE_2026_09_23__ what the retired session had in flight (LIVE records
        // not yet acknowledged) moves to the survivor instead of waiting for the next diff (C.11).
        var carried: [Data] = []
        if let existing = activeByPeer[peer], existing !== s, existing.state != .closed {
            if keepFirst(existing, s) {
                log("session with \(peer.prefix(8)): keeping \(existing.link.transport), closing the new \(s.link.transport) one (duplicate)")
                s.sendRecord(DSWire.BYE, ["reason": "duplicate"])
                s.close("duplicate", silent: true)
                return
            }
            let sLan = s.link.transport == DSKeys.transportLAN, eLan = existing.link.transport == DSKeys.transportLAN
            let reason = sLan && !eLan ? "lan" : (eLan && !sLan ? "timeout" : "duplicate")
            carried += existing.takeInFlightLive()
            existing.sendRecord(DSWire.BYE, ["reason": reason])
            existing.close(reason, silent: true)
            if reason != "duplicate" {
                log("session with \(peer.prefix(8)) moved \(existing.link.transport) -> \(s.link.transport) (\(carried.count) live record(s) carried)")
            }
        }
        activeByPeer[peer] = s
        carried = takeHandover(peer) + carried
        s.authorize()
        if !carried.isEmpty { s.adoptLive(carried) }
        notifySession(s)
        if s.link.transport != DSKeys.transportLAN { lanUpgradeProbe?() }
    }

    private func stashHandover(_ peer: String, _ bodies: [Data]) {
        guard !bodies.isEmpty else { return }
        handover[peer] = (DSTime.monotonicMs(), (handover[peer]?.1 ?? []) + bodies)
    }

    private func takeHandover(_ peer: String) -> [Data] {
        guard let (at, bodies) = handover.removeValue(forKey: peer) else { return [] }
        return DSTime.monotonicMs() - at <= Self.handoverTtlMs ? bodies : []
    }

    /// LAN beats relay — unless the LAN session is stale (C.14: its keepalive-capable peer went
    /// silent, i.e. the peer dialled the relay because ITS side of the LAN died); otherwise keep the
    /// one initiated by the smaller deviceId; same initiator = the newer one wins.
    private func keepFirst(_ a: DSSession, _ b: DSSession) -> Bool {
        let aLan = a.link.transport == DSKeys.transportLAN && !a.lanStale()
        let bLan = b.link.transport == DSKeys.transportLAN && !b.lanStale()
        if aLan != bLan { return aLan }
        if a.initiatorId == b.initiatorId { return false }
        let smaller = min(deviceId, a.peerId!)
        return a.initiatorId == smaller
    }

    func onPairingNeeded(_ s: DSSession) {
        guard let peer = s.peerId, let hello = s.peerHello else { return }
        if (deniedUntil[peer] ?? 0) > now() { s.close("denied-recently", silent: true); return }
        let fresh = registry.isEmpty
        let req = DSPairingRequest(peerId: peer, name: hello.name, platform: hello.platform, code: s.sasCode ?? "",
                                   transport: s.link.transport, thisDeviceIsNew: fresh)
        pending[peer] = req
        delegate?.devsyncPairingRequest(req)
        if !fresh {
            let last = lastAlertAt[peer] ?? 0
            if now() - last >= Self.alertQuietMs {
                lastAlertAt[peer] = now()
                delegate?.devsyncAlert(kind: "unapproved-device", deviceName: hello.name)
            }
        }
        notifySession(s)
    }

    func acceptsApprovalFromUnknown() -> Bool { registry.isEmpty }

    func linkPeer(_ s: DSSession, approvedBy: String?) {
        guard let pid = s.peerId, let hello = s.peerHello, let ps = s.peerStatic else { return }
        registry.link(DSLinkedDevice(deviceId: pid, dk: DSCrypto.hex(ps), name: hello.name, platform: hello.platform,
                                     approvedAtMs: now(), approvedBy: approvedBy))
        pending.removeValue(forKey: pid)
        delegate?.devsyncPairingResolved(peerId: pid, linked: true)
        delegate?.devsyncDevicesChanged()
        delegate?.devsyncAlert(kind: "device-linked", deviceName: hello.name)
    }

    func onPeerApprovedAwaitingLocal(_ s: DSSession) {
        guard let peer = s.peerId, var p = pending[peer] else { return }
        p.peerApproved = true
        pending[peer] = p
        delegate?.devsyncPairingRequest(p)
    }

    func onPairingRefusedByPeer(_ s: DSSession) {
        guard let peer = s.peerId else { return }
        pending.removeValue(forKey: peer)
        delegate?.devsyncPairingResolved(peerId: peer, linked: false)
    }

    func onSyncStarted(_ s: DSSession) {
        if let p = s.peerId { pending.removeValue(forKey: p) }
        notifySession(s)
    }

    func onApplied(_ s: DSSession, _ conv: SyncConversation, _ r: DSApplyResult) {
        delegate?.devsyncApplied(peerId: s.peerId ?? "", conversationId: conv.id, result: r)
    }

    func onPullComplete(_ s: DSSession) { notifySession(s); maybeSynced(s) }
    func onMediaComplete(_ s: DSSession) { notifySession(s); maybeSynced(s) }
    func onMediaFetched(_ s: DSSession, _ sha: String) { notifySession(s) }

    private func maybeSynced(_ s: DSSession) {
        guard s.pullComplete, s.mediaComplete, let hello = s.peerHello, let pid = s.peerId else { return }
        registry.touch(pid, name: hello.name, platform: hello.platform, syncedAtMs: now())
        delegate?.devsyncSynced(peerId: pid, stats: s.stats)
    }

    func onRevokedByPeer(_ s: DSSession) { delegate?.devsyncAlert(kind: "revoked-by-peer", deviceName: s.peerHello?.name ?? "") }
    func onDevicesChanged() { delegate?.devsyncDevicesChanged() }

    func onSessionClosed(_ s: DSSession, _ reason: String) {
        sessions.removeAll { $0 === s }
        if let peer = s.peerId, activeByPeer[peer] === s {
            activeByPeer.removeValue(forKey: peer)
            if pending.removeValue(forKey: peer) != nil {
                delegate?.devsyncPairingResolved(peerId: peer, linked: registry.isLinked(peer))
            }
        }
        notifySession(s)
        sessionClosedHooks.forEach { $0(s) }
        guard let peer = s.peerId, running else { return }
        // The peer moved this session to LAN (BYE{lan}): keep what was in flight for it.
        if reason == "bye:lan" { stashHandover(peer, s.takeInFlightLive()) }
        // §5.3 step 5: LAN dropped (EOF, keepalive silence, error) → relay at once (smaller id dials).
        if s.link.transport == DSKeys.transportLAN, !Self.noFallback.contains(reason),
           registry.isLinked(peer), activeSession(peer) == nil {
            relayDialSoon?(peer)
        }
    }

    private func notifySession(_ s: DSSession) { delegate?.devsyncSessionChanged(s.info) }

    // ---------------------------------------------------------------- policy (§10)

    func mediaAllowed(with peer: DSHello?, transport: String) -> Bool {
        let net = config.network()
        let lan = transport == DSKeys.transportLAN
        if net.lowPower { return false }
        switch config.mediaPolicy {
        case DSHello.mediaNever: return false
        case DSHello.mediaUnmetered: if net.metered && !lan { return false }
        default: break
        }
        guard let peer else { return true }
        if peer.lowPower { return false }
        switch peer.media {
        case DSHello.mediaNever: return false
        case DSHello.mediaUnmetered: return !peer.metered || lan
        default: return true
        }
    }

    func partialFile(_ sha: String) -> URL {
        config.cacheDir.appendingPathComponent("devsync/partial", isDirectory: true).appendingPathComponent(sha)
    }

    /// One-entry cache so a large file is not re-read for every MEDIA_GET.
    func openMediaCached(_ sha: String) -> Data? {
        if let c = mediaCache, c.0 == sha { return c.1 }
        guard let d = store.openMedia(sha) else { return nil }
        mediaCache = (sha, d)
        return d
    }

    private func sweep() {
        let t = now()
        for s in sessions where s.state != .closed && t - s.startedAtMs > config.sessionMaxMs {
            s.closeGracefully("timeout")
        }
        maintainHooks.forEach { $0() }
        // Safety net (§9): re-diff open sessions; an unchanged SUMMARY is not re-sent.
        for s in Array(activeByPeer.values) { s.resync(nil, deferWhilePulling: false, skipUnchanged: true) }
        // Still on the relay with a peer that may now be on the LAN: try its known LAN endpoints.
        if activeByPeer.values.contains(where: { $0.link.transport != DSKeys.transportLAN }) { lanUpgradeProbe?() }
    }
}

// MARK: Relay hub (design §5.2) — one WebSocket, many peer sessions

protocol DSRelaySocket: AnyObject {
    func sendBinary(_ bytes: Data)
    func close()
}

final class DSRelayHub {
    static let backoffMs: [Int64] = [2_000, 5_000, 15_000, 60_000, 300_000]
    unowned let engine: DSEngine
    /// Opens the signed socket; callbacks may arrive on any thread (the hub posts them).
    let connector: (_ deviceId: String, _ onOpen: @escaping () -> Void, _ onText: @escaping (String) -> Void,
                    _ onBinary: @escaping (Data) -> Void, _ onClosed: @escaping (Int, String) -> Void) -> DSRelaySocket?
    private var socket: DSRelaySocket?
    private(set) var connected = false
    private(set) var online: [String] = []
    private var links: [String: DSRelayLink] = [:]
    private var attempt = 0
    private var reconnect: DSTimer?
    private var stopped = false
    private var dialTimers: [String: DSTimer] = [:]
    private var generation = 0

    init(engine: DSEngine, connector: @escaping (String, @escaping () -> Void, @escaping (String) -> Void, @escaping (Data) -> Void, @escaping (Int, String) -> Void) -> DSRelaySocket?) {
        self.engine = engine
        self.connector = connector
    }

    func start() { engine.post { self.connect() } }

    private func connect() {
        guard !stopped else { return }
        generation += 1
        let gen = generation
        let e = engine
        socket = connector(engine.deviceId,
            { e.post { [weak self] in guard let self, gen == self.generation else { return }; self.connected = true; self.attempt = 0; e.log("relay connected") } },
            { t in e.post { [weak self] in guard let self, gen == self.generation else { return }; self.onPresence(t) } },
            { b in e.post { [weak self] in guard let self, gen == self.generation else { return }; self.onFrame(b) } },
            { c, r in e.post { [weak self] in guard let self, gen == self.generation else { return }; self.onDisconnected(c, r) } })
        if socket == nil { onDisconnected(-1, "connect failed") }
    }

    private func onDisconnected(_ code: Int, _ reason: String) {
        connected = false
        socket = nil
        online.removeAll()
        for l in Array(links.values) { l.session?.close("relay-down", silent: true) }
        links.removeAll()
        guard !stopped else { return }
        let delay = Self.backoffMs[min(attempt, Self.backoffMs.count - 1)]
        attempt += 1
        reconnect?.cancel()
        reconnect = engine.schedule(delay) { [weak self] in self?.connect() }
        engine.log("relay closed (\(code) \(reason)), retry in \(delay / 1000)s")
    }

    private func onPresence(_ text: String) {
        guard let o = DSJSON.obj(Data(text.utf8)) else { return }
        switch DSJSON.string(o, "t") {
        case "peers":
            online = (o["d"] as? [Any] ?? []).compactMap { $0 as? String }.filter { DSKeys.isDeviceIdHex($0) }
            online.forEach { scheduleDial($0) }
        case "up":
            let id = DSJSON.string(o, "d")
            if DSKeys.isDeviceIdHex(id) { if !online.contains(id) { online.append(id) }; scheduleDial(id) }
        case "down", "nopeer":
            let id = DSJSON.string(o, "d")
            online.removeAll { $0 == id }
            links.removeValue(forKey: id)?.session?.close(DSJSON.string(o, "t") == "down" ? "peer-down" : "nopeer", silent: true)
        default: break
        }
    }

    /// Appendix C.1: the SMALLER deviceId initiates (after the 3 s LAN grace). The first port copied
    /// the Kotlin hub, which let the LARGER id dial (fixed in both on 2026-09-23): a spec-following
    /// peer would then never dial back and both sides would wait forever.
    private func scheduleDial(_ peer: String, delayMs: Int64? = nil) {
        guard peer > engine.deviceId, !engine.registry.isRevoked(peer) else { return }
        dialTimers[peer]?.cancel()
        dialTimers[peer] = engine.schedule(delayMs ?? engine.config.relayDelayMs) { [weak self] in
            self?.dialTimers.removeValue(forKey: peer)
            self?.dial(peer)
        }
    }

    /// §5.3 step 5: the LAN session with `peer` just died — dial it through the relay without the
    /// usual 3 s LAN grace (LAN was just tried). No-op for the larger id.
    func dialSoon(_ peer: String) {
        guard connected, online.contains(peer) else { return }
        if peer > engine.deviceId { engine.log("LAN with \(peer.prefix(8)) gone: dialling it through the relay") }
        scheduleDial(peer, delayMs: engine.config.relayFallbackDelayMs)
    }

    func dial(_ peer: String) {
        guard connected, online.contains(peer), engine.activeSession(peer) == nil else { return }
        if let s = links[peer]?.session, s.state != .closed { return }
        let link = DSRelayLink(hub: self, peer: peer)
        links[peer] = link
        link.session = engine.newSession(link, initiator: true, expectedPeerId: peer)
    }

    private func onFrame(_ bytes: Data) {
        guard let f = DSWire.parseRelayFrame(bytes) else { return }
        let src = f.deviceIdHex
        guard src != engine.deviceId else { return }
        if !online.contains(src) { online.append(src) }
        if f.kind == DSWire.kindMsg1 {
            links.removeValue(forKey: src)?.session?.close("replaced", silent: true)
            if engine.registry.isRevoked(src) { return }
            let link = DSRelayLink(hub: self, peer: src)
            links[src] = link
            link.session = engine.newSession(link, initiator: false, expectedPeerId: src)
            link.session?.onFrame(kind: f.kind, f.noise)
            return
        }
        links[src]?.session?.onFrame(kind: f.kind, f.noise)
    }

    func send(_ peer: String, kind: Int, _ noise: Data) {
        guard let s = socket, let id = DSCrypto.unhex(peer) else { return }
        s.sendBinary(DSWire.relayFrame(id, kind: kind, noise))
    }

    func detach(_ link: DSRelayLink) { if links[link.peer] === link { links.removeValue(forKey: link.peer) } }

    func onSessionClosed(_ s: DSSession) { if let l = s.link as? DSRelayLink { detach(l) } }

    func maintain() {
        guard connected else { return }
        for p in online where engine.activeSession(p) == nil && links[p] == nil { scheduleDial(p) }
    }

    func stop() {
        stopped = true
        reconnect?.cancel()
        dialTimers.values.forEach { $0.cancel() }
        socket?.close()
        socket = nil
        connected = false
    }
}

final class DSRelayLink: DSLink {
    let transport = DSKeys.transportRelay
    let label: String
    let peer: String
    weak var hub: DSRelayHub?
    var session: DSSession?
    init(hub: DSRelayHub, peer: String) { self.hub = hub; self.peer = peer; label = "relay:" + String(peer.prefix(8)) }
    func send(kind: Int, _ noise: Data) { hub?.send(peer, kind: kind, noise) }
    func close() { hub?.detach(self) }
}

// MARK: Device-bound relay upgrade (__DEVSYNC_DEVICE_AUTH_2026_09_23__, relay_auth.json)

/// Port of Kotlin `DevSyncRelayAuth` / server `devsync_relay.js`. The upgrade proves BOTH the
/// account key and this install's device signing key `dsk` (the per-device-mailbox key):
///
///     B       = "OSHI-DEVSYNC-UPGRADE/1\n<identity>\n<deviceId>\n<dk>\n<dsk>\n<nonce>"
///     account = Ed25519(account key, "GET\n/v2/devsync\n<sha256hex(B)>\n<ts>")   (B = the hashed body)
///     device  = Ed25519(dsk, "OSHI-DEVICE/1\n<deviceId>\nGET\n/v2/devsync\n<sha256hex(B)>\n<ts>")
///
/// The random nonce makes every upgrade unique: two devices of one account connecting in the
/// same millisecond no longer collide on the relay's replay cache, and an account-key holder
/// can no longer connect AS another device id.
enum DSRelayAuth {
    static let tag = "OSHI-DEVSYNC-UPGRADE/1"
    static let path = "/v2/devsync"
    static let hDevice = "x-oshi-device", hDeviceSig = "x-oshi-device-signature"
    static let hDk = "x-oshi-device-dk", hDsk = "x-oshi-device-dsk", hNonce = "x-oshi-devsync-nonce"

    static func bindingString(identity: String, deviceId: String, dk: String, dsk: String, nonce: String) -> String {
        "\(tag)\n\(identity)\n\(deviceId)\n\(dk)\n\(dsk)\n\(nonce)"
    }

    static func accountString(bindingSha256Hex: String, ts: String) -> String { "GET\n\(path)\n\(bindingSha256Hex)\n\(ts)" }

    static func deviceString(deviceId: String, bindingSha256Hex: String, ts: String) -> String {
        "OSHI-DEVICE/1\n\(deviceId)\nGET\n\(path)\n\(bindingSha256Hex)\n\(ts)"
    }

    static func newNonce() -> String { DSCrypto.hex(DSCrypto.random(16)) }

    /// The server's `normalizeIdentity`: %2B/%2F/%3D undone, base64url -> standard, re-padded,
    /// only when it decodes to 32 bytes; otherwise trimmed and untouched.
    static func normalizeIdentity(_ s: String) -> String {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        var k = t.replacingOccurrences(of: "%2B", with: "+", options: .caseInsensitive)
            .replacingOccurrences(of: "%2F", with: "/", options: .caseInsensitive)
            .replacingOccurrences(of: "%3D", with: "=", options: .caseInsensitive)
            .replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while k.hasSuffix("=") { k.removeLast() }
        while k.count % 4 != 0 { k += "=" }
        if let d = Data(base64Encoded: k), d.count == 32 { return d.base64EncodedString() }
        return t
    }

    /// All upgrade headers. `accountSign(B)` is the platform's account request signer, called
    /// ONCE with body = B; it returns x-oshi-user / -signing-pubkey / -signature / -timestamp.
    /// `dskSign` signs with the private device key (64 raw bytes).
    static func upgradeHeaders(identity: String, deviceId: String, dkPub: Data, dskPub: Data,
                               dskSign: (Data) throws -> Data,
                               accountSign: (Data) throws -> [String: String],
                               nonce: String = newNonce()) throws -> [String: String] {
        guard DSKeys.deviceIdHex(dkPub) == deviceId else { throw DevSyncError("deviceId is not H(dk)") }
        guard dskPub.count == 32 else { throw DevSyncError("dsk must be 32 bytes") }
        guard nonce.count == 32, nonce.allSatisfy({ ("0"..."9").contains($0) || ("a"..."f").contains($0) }) else {
            throw DevSyncError("nonce must be 32 lowercase hex")
        }
        let id = normalizeIdentity(identity)
        let dk = dkPub.base64EncodedString(), dsk = dskPub.base64EncodedString()
        let binding = Data(bindingString(identity: id, deviceId: deviceId, dk: dk, dsk: dsk, nonce: nonce).utf8)
        var h = try accountSign(binding)
        guard let ts = h["x-oshi-timestamp"] else { throw DevSyncError("account signer returned no x-oshi-timestamp") }
        guard let user = h["x-oshi-user"], normalizeIdentity(user) == id else { throw DevSyncError("x-oshi-user is not the identity inside B") }
        let bh = DSCrypto.hex(DSCrypto.sha256(binding))
        let sig = try dskSign(Data(deviceString(deviceId: deviceId, bindingSha256Hex: bh, ts: ts).utf8))
        h[hDevice] = deviceId
        h[hDk] = dk
        h[hDsk] = dsk
        h[hNonce] = nonce
        h[hDeviceSig] = sig.base64EncodedString()
        return h
    }
}
// ===DEVSYNC-CORE-END===
// ===DEVSYNC-VECTORS-BEGIN===
// MARK: - Golden-vector check (docs/fixtures/devsync/*.json) + in-process loopback

enum DevSyncVectorCheck {
    private final class Out {
        var lines: [String] = []
        var pass = 0, fail = 0
        func check(_ name: String, _ ok: Bool, _ detail: @autoclosure () -> String = "") {
            if ok { pass += 1; lines.append("PASS \(name)") } else {
                fail += 1
                let d = detail()
                lines.append("FAIL \(name)\(d.isEmpty ? "" : " — " + d)")
            }
        }
    }

    private static func load(_ dir: URL, _ name: String) -> DSObj? {
        guard let d = try? Data(contentsOf: dir.appendingPathComponent(name)) else { return nil }
        return DSJSON.obj(d)
    }

    private static func hx(_ o: DSObj, _ k: String) -> Data { DSCrypto.unhex(DSJSON.string(o, k)) ?? Data() }

    /// Runs every vector file in `dir`. Returns report lines (PASS/FAIL) and the totals.
    static func run(dir: URL) -> (lines: [String], pass: Int, fail: Int) {
        let out = Out()
        keys(dir, out)
        handshake(dir, out)
        transport(dir, out)
        framing(dir, out)
        diff(dir, out)
        merge(dir, out)
        relayAuth(dir, out)
        loopback(out)
        out.lines.append("TOTAL pass=\(out.pass) fail=\(out.fail)")
        return (out.lines, out.pass, out.fail)
    }

    // ------------------------------------------------------------------ keys.json
    private static func keys(_ dir: URL, _ out: Out) {
        guard let v = load(dir, "keys.json") else { out.check("keys.json.present", false); return }
        let acct = v["account"] as? DSObj ?? [:]
        let ik = hx(acct, "ikPriv")
        out.check("keys.psk", DSCrypto.hex(DSKeys.psk(ik)) == DSJSON.string(acct, "psk"))
        out.check("keys.disc", DSCrypto.hex(DSKeys.disc(ik)) == DSJSON.string(acct, "disc"))
        out.check("keys.ikPub", (try? DSCrypto.hex(DSCrypto.x25519Public(ik))) == DSJSON.string(acct, "ikPub"))
        let other = v["otherAccount"] as? DSObj ?? [:]
        out.check("keys.otherPsk", DSCrypto.hex(DSKeys.psk(hx(other, "ikPriv"))) == DSJSON.string(other, "psk"))
        for (name, dv) in (v["devices"] as? DSObj ?? [:]).sorted(by: { $0.key < $1.key }) {
            guard let d = dv as? DSObj else { continue }
            let pub = (try? DSCrypto.x25519Public(hx(d, "priv"))) ?? Data()
            out.check("keys.device\(name).pub", DSCrypto.hex(pub) == DSJSON.string(d, "pub"))
            out.check("keys.device\(name).id", DSKeys.deviceIdHex(pub) == DSJSON.string(d, "deviceId"))
            // __DEVSYNC_DEVICE_AUTH_2026_09_23__ dskSeed = SHA-256("OSHI-DEVSYNC vector dsk" ‖ dk_priv), dsk = its Ed25519 public.
            if d["dsk"] != nil {
                out.check("keys.device\(name).dskSeed", DSCrypto.hex(DSCrypto.sha256(Data("OSHI-DEVSYNC vector dsk".utf8), hx(d, "priv"))) == DSJSON.string(d, "dskSeed"))
                out.check("keys.device\(name).dsk", ed25519Pub(hx(d, "dskSeed")) == DSJSON.string(d, "dsk"))
            }
        }
        let disc = DSKeys.disc(ik)
        for t in DSJSON.objs(v, "mdnsTags") {
            let s = DSJSON.int64(t, "unixSeconds") ?? 0
            out.check("keys.mdnsTag@\(s)", DSKeys.mdnsTag(disc, unixSeconds: s) == DSJSON.string(t, "tag"))
        }
        for (i, s) in DSJSON.objs(v, "sas").enumerated() {
            out.check("keys.sas[\(i)]", DSKeys.sasCode(hx(s, "handshakeHash")) == DSJSON.string(s, "code"))
        }
    }

    private static func ed25519Pub(_ seed: Data) -> String {
        ((try? Curve25519.Signing.PrivateKey(rawRepresentation: seed))?.publicKey.rawRepresentation ?? Data()).base64EncodedString()
    }

    // ------------------------------------------------------------------ relay_auth.json
    // __DEVSYNC_DEVICE_AUTH_2026_09_23__ the device-bound relay upgrade. Ed25519 in CryptoKit is
    // randomized, so signatures are checked as: the vector signature verifies under the key,
    // and ours (over the byte-identical string) verifies too.
    private static func relayAuth(_ dir: URL, _ out: Out) {
        guard let v = load(dir, "relay_auth.json") else { out.check("relay_auth.json.present", false); return }
        for c in DSJSON.objs(v, "cases") {
            let n = "relay." + DSJSON.string(c, "name")
            let inp = c["inputs"] as? DSObj ?? [:]
            guard let acct = try? Curve25519.Signing.PrivateKey(rawRepresentation: hx(inp, "accountSigningSeed")),
                  let dsk = try? Curve25519.Signing.PrivateKey(rawRepresentation: hx(inp, "dskSeed")) else {
                out.check(n + ".keys", false); continue
            }
            let dkPub = hx(inp, "dkPub")
            let identity = DSJSON.string(inp, "identity"), ts = DSJSON.string(inp, "ts"), nonce = DSJSON.string(inp, "nonce")
            let deviceId = DSKeys.deviceIdHex(dkPub)
            out.check(n + ".deviceId", deviceId == DSJSON.string(c, "deviceId"))
            out.check(n + ".dk", dkPub.base64EncodedString() == DSJSON.string(c, "dk"))
            out.check(n + ".dsk", dsk.publicKey.rawRepresentation.base64EncodedString() == DSJSON.string(c, "dsk"))
            var accountString = ""
            let headers = try? DSRelayAuth.upgradeHeaders(
                identity: identity, deviceId: deviceId, dkPub: dkPub, dskPub: dsk.publicKey.rawRepresentation,
                dskSign: { try dsk.signature(for: $0) },
                accountSign: { body in
                    accountString = DSRelayAuth.accountString(bindingSha256Hex: DSCrypto.hex(DSCrypto.sha256(body)), ts: ts)
                    return ["x-oshi-user": identity,
                            "x-oshi-signing-pubkey": acct.publicKey.rawRepresentation.base64EncodedString(),
                            "x-oshi-signature": try acct.signature(for: Data(accountString.utf8)).base64EncodedString(),
                            "x-oshi-timestamp": ts]
                }, nonce: nonce)
            guard let h = headers else { out.check(n + ".headers", false); continue }
            let binding = DSRelayAuth.bindingString(identity: DSRelayAuth.normalizeIdentity(identity), deviceId: deviceId,
                                                    dk: dkPub.base64EncodedString(), dsk: dsk.publicKey.rawRepresentation.base64EncodedString(), nonce: nonce)
            out.check(n + ".binding", binding == DSJSON.string(c, "binding"))
            let bh = DSCrypto.hex(DSCrypto.sha256(Data(binding.utf8)))
            out.check(n + ".bindingSha256", bh == DSJSON.string(c, "bindingSha256"))
            out.check(n + ".accountString", accountString == DSJSON.string(c, "accountString"))
            let devStr = DSRelayAuth.deviceString(deviceId: deviceId, bindingSha256Hex: bh, ts: ts)
            out.check(n + ".deviceString", devStr == DSJSON.string(c, "deviceString"))
            func verifies(_ k: Curve25519.Signing.PublicKey, _ sig: String?, _ msg: String) -> Bool {
                guard let sig, let d = Data(base64Encoded: sig) else { return false }
                return k.isValidSignature(d, for: Data(msg.utf8))
            }
            out.check(n + ".accountSignature", verifies(acct.publicKey, DSJSON.string(c, "accountSignature"), accountString)
                      && verifies(acct.publicKey, h["x-oshi-signature"], accountString))
            out.check(n + ".deviceSignature", verifies(dsk.publicKey, DSJSON.string(c, "deviceSignature"), devStr)
                      && verifies(dsk.publicKey, h[DSRelayAuth.hDeviceSig], devStr))
            // Every non-signature header byte-identical; signature headers checked above.
            let want = c["headers"] as? DSObj ?? [:]
            let sigHeaders: Set<String> = ["x-oshi-signature", DSRelayAuth.hDeviceSig]
            let same = want.keys.sorted().allSatisfy { k in sigHeaders.contains(k) || h[k] == (want[k] as? String) }
            out.check(n + ".headers", same && Set(want.keys) == Set(h.keys), "\(h)")
            out.check(n + ".requestTarget", "\(DSRelayAuth.path)?device=\(deviceId)" == DSJSON.string(c, "requestTarget"))
            // Negative: a wrong deviceId or a non-hex nonce is refused before signing.
            out.check(n + ".refusesUnboundId", (try? DSRelayAuth.upgradeHeaders(
                identity: identity, deviceId: String(repeating: "0", count: 32), dkPub: dkPub, dskPub: dsk.publicKey.rawRepresentation,
                dskSign: { try dsk.signature(for: $0) }, accountSign: { _ in [:] }, nonce: nonce)) == nil)
        }
        out.check("relay.normalizeIdentity", DSRelayAuth.normalizeIdentity("ERERERERERERERERERERERERERERERERERERERERERE%3D") == "ERERERERERERERERERERERERERERERERERERERERERE="
                  && DSRelayAuth.normalizeIdentity("ERERERERERERERERERERERERERERERERERERERERERE") == "ERERERERERERERERERERERERERERERERERERERERERE=")
        // Two upgrades of one device in the same millisecond differ (fresh nonce).
        out.check("relay.freshNonce", DSRelayAuth.newNonce() != DSRelayAuth.newNonce() && DSRelayAuth.newNonce().count == 32)
    }

    // ------------------------------------------------------------------ noise_handshake.json
    private static func handshake(_ dir: URL, _ out: Out) {
        guard let v = load(dir, "noise_handshake.json") else { out.check("noise_handshake.json.present", false); return }
        let keysV = load(dir, "keys.json")?["devices"] as? DSObj ?? [:]
        func devId(_ n: String) -> Data? { DSCrypto.unhex(DSJSON.string(keysV[n] as? DSObj ?? [:], "deviceId")) }
        for c in DSJSON.objs(v, "cases") {
            let name = DSJSON.string(c, "name")
            let transport = DSJSON.string(c, "transport")
            let prologue = hx(c, "prologue")
            if name == "relay" || name == "lan" {
                let computed = transport == "lan" ? DSKeys.prologue("lan")
                    : DSKeys.prologue("relay", devId(DSJSON.string(c, "initiator")) ?? Data(), devId(DSJSON.string(c, "responder")) ?? Data())
                out.check("hs.\(name).prologue", computed == prologue)
            }
            do {
                let ini = try DSNoiseHandshake(initiator: true, staticPriv: hx(c, "initiatorStaticPriv"), psk: hx(c, "psk"),
                                               prologue: prologue, ephemeralPriv: hx(c, "initiatorEphemeralPriv"))
                let rPsk = c["responderPsk"] != nil ? hx(c, "responderPsk") : hx(c, "psk")
                let rPro = c["responderPrologue"] != nil ? hx(c, "responderPrologue") : prologue
                let res = try DSNoiseHandshake(initiator: false, staticPriv: hx(c, "responderStaticPriv"), psk: rPsk,
                                               prologue: rPro, ephemeralPriv: hx(c, "responderEphemeralPriv"))
                let m1v = c["msg1"] as? DSObj ?? [:]
                let m1 = try ini.writeMessage1(hx(m1v, "payload"))
                out.check("hs.\(name).msg1", DSCrypto.hex(m1) == DSJSON.string(m1v, "message"))
                out.check("hs.\(name).msg1.h", DSCrypto.hex(ini.handshakeHash) == DSJSON.string(m1v, "hAfter"))
                if c["expect"] != nil {
                    let rejected = (try? res.readMessage1(m1)) == nil
                    out.check("hs.\(name).responderRejectsMsg1", rejected)
                    continue
                }
                let p1 = try res.readMessage1(m1)
                out.check("hs.\(name).msg1.read", p1 == hx(m1v, "payload") && res.handshakeHash == ini.handshakeHash)
                let m2v = c["msg2"] as? DSObj ?? [:]
                let m2 = try res.writeMessage2(hx(m2v, "payload"))
                out.check("hs.\(name).msg2", DSCrypto.hex(m2) == DSJSON.string(m2v, "message"))
                let p2 = try ini.readMessage2(m2)
                out.check("hs.\(name).msg2.read", p2 == hx(m2v, "payload") && DSCrypto.hex(ini.handshakeHash) == DSJSON.string(m2v, "hAfter"))
                let m3v = c["msg3"] as? DSObj ?? [:]
                let m3 = try ini.writeMessage3(hx(m3v, "payload"))
                out.check("hs.\(name).msg3", DSCrypto.hex(m3) == DSJSON.string(m3v, "message"))
                let p3 = try res.readMessage3(m3)
                out.check("hs.\(name).msg3.read", p3 == hx(m3v, "payload") && DSCrypto.hex(res.handshakeHash) == DSJSON.string(m3v, "hAfter"))
                out.check("hs.\(name).handshakeHash", DSCrypto.hex(ini.handshakeHash) == DSJSON.string(c, "handshakeHash"))
                out.check("hs.\(name).sas", DSKeys.sasCode(ini.handshakeHash) == DSJSON.string(c, "sasCode"))
                let (iS, iR) = ini.split()
                let (rS, rR) = res.split()
                out.check("hs.\(name).i2rKey", DSCrypto.hex(iS.cs.k ?? Data()) == DSJSON.string(c, "initiatorToResponderKey") && iS.cs.k == rR.cs.k)
                out.check("hs.\(name).r2iKey", DSCrypto.hex(rS.cs.k ?? Data()) == DSJSON.string(c, "responderToInitiatorKey") && rS.cs.k == iR.cs.k)
                out.check("hs.\(name).remoteStatics", ini.remoteStatic == res.sPub && res.remoteStatic == ini.sPub)
                // HELLO payloads decode, and deviceId == H(remote static).
                if let h2 = DSHello.decode(p2), let h3 = DSHello.decode(p3) {
                    out.check("hs.\(name).helloBinding", h2.deviceId == DSKeys.deviceIdHex(ini.remoteStatic!) && h3.deviceId == DSKeys.deviceIdHex(res.remoteStatic!))
                    // __DEVSYNC_DEVICE_AUTH_2026_09_23__ HELLO carries the sender's dsk (keys.json devices.*.dsk).
                    func dskOf(_ k: String) -> String { ed25519Pub(DSCrypto.sha256(Data("OSHI-DEVSYNC vector dsk".utf8), hx(c, k))) }
                    out.check("hs.\(name).helloDsk", h2.dsk == dskOf("responderStaticPriv") && h3.dsk == dskOf("initiatorStaticPriv"),
                              "\(h2.dsk ?? "nil") \(h3.dsk ?? "nil")")
                    // Our encoder re-emits the same field: HELLO(extra: dsk) round-trips.
                    var mine = DSHello(deviceId: h2.deviceId, name: "x", platform: "ios")
                    mine.extra = ["dsk": h2.dsk ?? ""]
                    out.check("hs.\(name).helloDskEncode", DSHello.decode(mine.encode())?.dsk == h2.dsk)
                } else { out.check("hs.\(name).helloDecode", false) }
            } catch {
                out.check("hs.\(name)", false, "\(error)")
            }
        }
    }

    // ------------------------------------------------------------------ noise_transport.json
    private static func transport(_ dir: URL, _ out: Out) {
        guard let v = load(dir, "noise_transport.json"), let hsV = load(dir, "noise_handshake.json") else {
            out.check("noise_transport.json.present", false); return
        }
        let from = DSJSON.string(v, "fromHandshake", "lan")
        guard let c = DSJSON.objs(hsV, "cases").first(where: { DSJSON.string($0, "name") == from }) else { out.check("transport.case", false); return }
        let i2r = DSTransportCipher(DSCipherState(hx(c, "initiatorToResponderKey")))
        let i2rRecv = DSTransportCipher(DSCipherState(hx(c, "initiatorToResponderKey")))
        for (i, m) in DSJSON.objs(v, "initiatorToResponder").enumerated() {
            let ct = try? i2r.encrypt(hx(m, "plaintext"))
            out.check("transport.i2r[\(i)]", ct.map(DSCrypto.hex) == DSJSON.string(m, "ciphertext"))
            out.check("transport.i2r[\(i)].open", (try? i2rRecv.decrypt(hx(m, "ciphertext"))) == hx(m, "plaintext"))
        }
        let r2i = DSTransportCipher(DSCipherState(hx(c, "responderToInitiatorKey")))
        for (i, m) in DSJSON.objs(v, "responderToInitiator").enumerated() {
            out.check("transport.r2i[\(i)]", (try? r2i.encrypt(hx(m, "plaintext"))).map(DSCrypto.hex) == DSJSON.string(m, "ciphertext"))
        }
        if let rk = v["rekeyFunction"] as? DSObj {
            let cs = DSCipherState(hx(rk, "keyBefore"))
            try? cs.rekey()
            out.check("transport.rekeyFunction", DSCrypto.hex(cs.k ?? Data()) == DSJSON.string(rk, "keyAfter"))
        }
        for name in ["rekeyByMessages", "rekeyByBytes"] {
            guard let seq = v[name] as? DSObj, let pol = seq["policy"] as? DSObj else { out.check("transport.\(name)", false); continue }
            let tc = DSTransportCipher(DSCipherState(hx(seq, "initialKey")), maxMessages: DSJSON.int64(pol, "maxMessages") ?? 0,
                                       maxBytes: DSJSON.int64(pol, "maxBytes") ?? 0)
            let rc = DSTransportCipher(DSCipherState(hx(seq, "initialKey")), maxMessages: DSJSON.int64(pol, "maxMessages") ?? 0,
                                       maxBytes: DSJSON.int64(pol, "maxBytes") ?? 0)
            for (i, m) in DSJSON.objs(seq, "messages").enumerated() {
                let used = DSCrypto.hex(tc.cs.k ?? Data()) == DSJSON.string(m, "keyUsed")
                let nonceOK = m["nonce"] == nil || Int64(tc.cs.n) == DSJSON.int64(m, "nonce")
                let ct = try? tc.encrypt(hx(m, "plaintext"))
                let pt = try? rc.decrypt(hx(m, "ciphertext"))
                out.check("transport.\(name)[\(i)]", used && nonceOK && ct.map(DSCrypto.hex) == DSJSON.string(m, "ciphertext")
                          && DSCrypto.hex(tc.cs.k ?? Data()) == DSJSON.string(m, "keyAfter") && pt == hx(m, "plaintext"))
            }
        }
    }

    // ------------------------------------------------------------------ framing.json
    private static func framing(_ dir: URL, _ out: Out) {
        guard let v = load(dir, "framing.json") else { out.check("framing.json.present", false); return }
        for (i, r) in DSJSON.objs(v, "records").enumerated() {
            let enc = DSWire.encodeRecord(type: Int(DSJSON.int64(r, "type") ?? 0), flags: Int(DSJSON.int64(r, "flags") ?? 0),
                                          seq: UInt32(DSJSON.int64(r, "seq") ?? 0), body: Data(DSJSON.string(r, "body").utf8))
            out.check("framing.record[\(i)]", DSCrypto.hex(enc) == DSJSON.string(r, "bytes"))
            if let dec = try? DSWire.decodeRecord(hx(r, "bytes")) {
                out.check("framing.record[\(i)].decode", dec.type == Int(DSJSON.int64(r, "type") ?? -1) && dec.seq == UInt32(DSJSON.int64(r, "seq") ?? 0)
                          && dec.continued == ((DSJSON.int64(r, "flags") ?? 0) & 1 == 1) && dec.body == Data(DSJSON.string(r, "body").utf8))
            } else { out.check("framing.record[\(i)].decode", false) }
        }
        if let l = v["lanFrame"] as? DSObj { out.check("framing.lan", DSCrypto.hex(DSWire.lanFrame(hx(l, "noise"))) == DSJSON.string(l, "bytes")) }
        if let r = v["relayOutbound"] as? DSObj {
            out.check("framing.relayOut", DSCrypto.hex(DSWire.relayFrame(hx(r, "destination"), kind: Int(DSJSON.int64(r, "kind") ?? 0), hx(r, "noise"))) == DSJSON.string(r, "bytes"))
        }
        if let r = v["relayInbound"] as? DSObj, let f = DSWire.parseRelayFrame(hx(r, "bytes")) {
            out.check("framing.relayIn", f.deviceIdHex == DSJSON.string(r, "source") && f.kind == Int(DSJSON.int64(r, "kind") ?? 0) && f.noise == hx(r, "noise"))
        } else { out.check("framing.relayIn", false) }
        if let m = v["mediaChunk"] as? DSObj {
            let body = DSWire.encodeMediaChunk(sha256: hx(m, "sha256"), offset: DSJSON.int64(m, "offset") ?? 0, last: DSJSON.bool(m, "last"), data: hx(m, "data"))
            out.check("framing.mediaChunk.body", DSCrypto.hex(body) == DSJSON.string(m, "body"))
            let rec = hx(m, "record")
            if let dec = try? DSWire.decodeRecord(rec) {
                out.check("framing.mediaChunk.record", DSCrypto.hex(DSWire.encodeRecord(type: DSWire.MEDIA_CHUNK, flags: 0, seq: dec.seq, body: body)) == DSJSON.string(m, "record") && dec.type == DSWire.MEDIA_CHUNK)
                if let mc = try? DSWire.decodeMediaChunk(dec.body) {
                    out.check("framing.mediaChunk.decode", mc.offset == DSJSON.int64(m, "offset") && mc.last == DSJSON.bool(m, "last") && mc.data == hx(m, "data"))
                }
            }
        }
        if let lim = v["limits"] as? DSObj {
            out.check("framing.limits", DSJSON.int64(lim, "maxNoiseMessage") == Int64(DSNoiseHandshake.maxMessage)
                      && DSJSON.int64(lim, "maxRecordPlaintext") == Int64(DSWire.maxRecord)
                      && DSJSON.int64(lim, "maxMediaChunkData") == Int64(DSWire.mediaChunkData)
                      && DSJSON.int64(lim, "relayMaxFrame") == Int64(DSNoiseHandshake.maxMessage + DSKeys.deviceIdBytes + 1))
        }
        // CONTINUED fragmentation round trip at the real limit.
        let big = Data((0..<(DSWire.maxBody * 2 + 17)).map { UInt8(truncatingIfNeeded: $0) })
        let frags = DSWire.fragment(big)
        let ra = DSWire.Reassembler()
        var got: Data? = nil
        for (i, f) in frags.enumerated() { got = try? ra.push(DSWire.Record(type: DSWire.MESSAGES, flags: f.0, seq: UInt32(i + 1), body: f.1)) }
        out.check("framing.continued.roundTrip", frags.count == 3 && got == big)
    }

    // ------------------------------------------------------------------ diff.json
    private static func parseSide(_ msgs: [DSObj], _ convs: [String: SyncConversation]) -> [SyncMessage] {
        msgs.compactMap { m in convs[DSJSON.string(m, "conversation")].flatMap { SyncCodec.messageFromJson(m, conv: $0) } }
    }

    private static func diff(_ dir: URL, _ out: Out) {
        guard let v = load(dir, "diff.json") else { out.check("diff.json.present", false); return }
        var convs: [String: SyncConversation] = [:]
        var convOrder: [SyncConversation] = []
        for c in DSJSON.objs(v, "conversations") {
            if let p = SyncCodec.conversationFromJson(c) { convs[DSJSON.string(c, "id")] = p; convOrder.append(p) }
            out.check("diff.conv.\(DSJSON.string(c, "id").prefix(14)).canonicalId", SyncCodec.conversationFromJson(c)?.id == DSJSON.string(c, "id"))
        }
        for r in DSJSON.objs(v, "revs") {
            guard let mo = r["message"] as? DSObj, let conv = convs[DSJSON.string(mo, "conversation")],
                  let m = SyncCodec.messageFromJson(mo, conv: conv) else { out.check("diff.rev.parse", false); continue }
            let id = DSJSON.string(r, "id")
            out.check("diff.canonical[\(id)]", SyncCodec.canonicalRev(m) == DSJSON.string(r, "canonical"), SyncCodec.canonicalRev(m))
            out.check("diff.rev[\(id)]", SyncCodec.rev(m) == DSJSON.string(r, "rev"))
        }
        var sides: [String: [SyncMessage]] = [:]
        for side in ["deviceA", "deviceB"] {
            guard let s = v[side] as? DSObj else { continue }
            let ms = parseSide(DSJSON.objs(s, "messages"), convs)
            sides[side] = ms
            // SUMMARY
            var summary: [DSObj] = []
            for c in convOrder {
                let cm = ms.filter { $0.conversation == c.id }
                if cm.isEmpty { continue }
                summary.append(["id": c.id, "kind": c.kind, "count": cm.count, "newest": DSTime.iso(cm.map(\.timestampMs).max()!),
                                "digest": SyncCodec.digest(cm.map { ($0.id, SyncCodec.rev($0)) })])
            }
            summary.sort { DSJSON.string($0, "newest") > DSJSON.string($1, "newest") }
            let exp = DSJSON.objs(s, "summary")
            out.check("diff.\(side).summary", summary.count == exp.count && zip(summary, exp).allSatisfy { a, b in
                DSJSON.string(a, "id") == DSJSON.string(b, "id") && DSJSON.int64(a, "count") == DSJSON.int64(b, "count")
                    && DSJSON.string(a, "newest") == DSJSON.string(b, "newest") && DSJSON.string(a, "digest") == DSJSON.string(b, "digest")
            })
            // BUCKETS + IDS
            for (cid, bv) in (s["buckets"] as? DSObj ?? [:]) {
                let cm = ms.filter { $0.conversation == cid }
                var byDay: [String: [SyncMessage]] = [:]
                for m in cm { byDay[DSTime.utcDay(m.timestampMs), default: []].append(m) }
                let mine = byDay.keys.sorted(by: >).map { d -> (String, Int, String) in
                    (d, byDay[d]!.count, SyncCodec.digest(byDay[d]!.map { ($0.id, SyncCodec.rev($0)) }))
                }
                let e = (bv as? [Any] ?? []).compactMap { $0 as? DSObj }
                out.check("diff.\(side).buckets[\(cid.prefix(14))]", mine.count == e.count && zip(mine, e).allSatisfy { a, b in
                    a.0 == DSJSON.string(b, "day") && Int64(a.1) == DSJSON.int64(b, "count") && a.2 == DSJSON.string(b, "digest")
                })
                let idsExp = (s["ids"] as? DSObj ?? [:])[cid] as? DSObj ?? [:]
                for (day, items) in idsExp {
                    let mineIds = (byDay[day] ?? []).sorted { CJ.utf8Less($0.idKey, $1.idKey) }.map { [$0.id, SyncCodec.rev($0)] }
                    let expIds = (items as? [Any] ?? []).compactMap { $0 as? [String] }
                    out.check("diff.\(side).ids[\(cid.prefix(14))/\(day)]", mineIds == expIds)
                }
            }
        }
        func wants(_ mine: [SyncMessage], _ theirs: [SyncMessage]) -> [String] {
            var have: [String: String] = [:]
            for m in mine { have[m.idKey] = SyncCodec.rev(m) }
            return theirs.filter { have[$0.idKey] != SyncCodec.rev($0) }.map(\.id).sorted { CJ.utf8Less(DSIds.asciiLower($0), DSIds.asciiLower($1)) }
        }
        let exp = v["expected"] as? DSObj ?? [:]
        let a = sides["deviceA"] ?? [], b = sides["deviceB"] ?? []
        out.check("diff.aWantsFromB", wants(a, b) == (exp["aWantsFromB"] as? [String] ?? []))
        out.check("diff.bWantsFromA", wants(b, a) == (exp["bWantsFromA"] as? [String] ?? []))
        if let ae = a.first(where: { $0.id == "edit-1" }), let be = b.first(where: { $0.id == "EDIT-1" }) {
            let merged = SyncMerge.message(ae, be)
            out.check("diff.mergedEdit1Rev", SyncCodec.rev(merged) == DSJSON.string(exp, "mergedEdit1Rev"))
            out.check("diff.mergedEdit1Commutes", SyncCodec.rev(SyncMerge.message(be, ae)) == SyncCodec.rev(merged) && DSJSON.bool(exp, "mergedEdit1RevCommutes"))
            if let mo = exp["mergedEdit1"] as? DSObj, let conv = convs[DSJSON.string(mo, "conversation")], let em = SyncCodec.messageFromJson(mo, conv: conv) {
                out.check("diff.mergedEdit1", em == merged)
            }
        } else { out.check("diff.mergedEdit1.present", false) }
    }

    // ------------------------------------------------------------------ merge.json
    private static func merge(_ dir: URL, _ out: Out) {
        guard let v = load(dir, "merge.json") else { out.check("merge.json.present", false); return }
        for c in DSJSON.objs(v, "messages") {
            let name = DSJSON.string(c, "name")
            guard let lo = c["local"] as? DSObj, let ro = c["remote"] as? DSObj, let mo = c["merged"] as? DSObj else { continue }
            let conv = SyncConversation.direct(String(DSJSON.string(lo, "conversation").dropFirst("direct:".count)))
            guard let l = SyncCodec.messageFromJson(lo, conv: conv), let r = SyncCodec.messageFromJson(ro, conv: conv),
                  let em = SyncCodec.messageFromJson(mo, conv: conv) else { out.check("merge.msg.\(name).parse", false); continue }
            let m = SyncMerge.message(l, r)
            out.check("merge.msg.\(name)", m == em && SyncCodec.rev(m) == DSJSON.string(c, "mergedRev"))
            out.check("merge.msg.\(name).commutes", SyncCodec.rev(SyncMerge.message(r, l)) == SyncCodec.rev(m))
            out.check("merge.msg.\(name).idempotent", SyncMerge.message(m, r) == m)
        }
        for c in DSJSON.objs(v, "contacts") {
            guard let l = (c["local"] as? DSObj).flatMap(SyncContact.from), let r = (c["remote"] as? DSObj).flatMap(SyncContact.from),
                  let e = (c["merged"] as? DSObj).flatMap(SyncContact.from) else { continue }
            out.check("merge.contact.\(DSJSON.string(c, "name"))", SyncMerge.contact(l, r) == e)
        }
        for (i, p) in DSJSON.objs(v, "profile").enumerated() {
            let m = SyncMerge.profile((p["local"] as? DSObj).flatMap(SyncProfile.from), (p["remote"] as? DSObj).flatMap(SyncProfile.from))
            let e = (p["merged"] as? DSObj).flatMap(SyncProfile.from)
            out.check("merge.profile[\(i)]", m?.name == e?.name && m?.updatedAtMs == e?.updatedAtMs)
        }
        for (i, p) in DSJSON.objs(v, "readState").enumerated() {
            guard let l = (p["local"] as? DSObj).flatMap(SyncReadState.from), let r = (p["remote"] as? DSObj).flatMap(SyncReadState.from) else { continue }
            out.check("merge.readState[\(i)]", SyncMerge.readState(l, r) == (p["merged"] as? DSObj).flatMap(SyncReadState.from))
        }
        for g in DSJSON.objs(v, "groups") {
            let name = DSJSON.string(g, "name")
            let l = (g["local"] as? DSObj).flatMap(SyncGroup.from)
            guard let r = (g["remote"] as? DSObj).flatMap(SyncGroup.from) else { continue }
            let m = SyncMerge.group(l, r)
            out.check("merge.group.\(name)", m.canonicalJson() == DSJSON.string(g, "canonical"), m.canonicalJson())
            if let l { out.check("merge.group.\(name).commutes", SyncMerge.group(r, l).canonicalJson() == m.canonicalJson()) }
        }
        if let d = v["devices"] as? DSObj {
            let selfId = DSJSON.string(d, "selfDeviceId")
            let st = DSMemoryStateStore()
            let reg = DSLinkedDeviceRegistry(state: st, selfId: selfId)
            let keysV = load(dir, "keys.json")?["devices"] as? DSObj ?? [:]
            for id in (d["local"] as? DSObj)?["linked"] as? [String] ?? [] {
                if let e = keysV.values.compactMap({ $0 as? DSObj }).first(where: { DSJSON.string($0, "deviceId") == id }) {
                    reg.link(DSLinkedDevice(deviceId: id, dk: DSJSON.string(e, "pub"), name: "Mac", platform: "desktop", approvedAtMs: 0))
                }
            }
            for (i, s) in DSJSON.objs(d, "steps").enumerated() {
                _ = reg.mergeDevicesRecord(DSDevicesRecord.from(s["record"] as? DSObj ?? [:]), from: "peer")
                let after = s["after"] as? DSObj ?? [:]
                out.check("merge.devices.step\(i)", reg.linked().map(\.deviceId).sorted() == (after["linked"] as? [String] ?? [])
                          && reg.revoked().sorted() == (after["revoked"] as? [String] ?? []))
            }
            let reloaded = DSLinkedDeviceRegistry(state: st, selfId: selfId)
            out.check("merge.devices.persisted", reloaded.linked().map(\.deviceId) == reg.linked().map(\.deviceId) && reloaded.revoked() == reg.revoked())
        }
        // __BLOCK_MUTE_RECORDS_2026_09_24__ record parity (parse -> write back), same cases as Kotlin.
        if let rec = v["records"] as? DSObj {
            for c in DSJSON.objs(rec, "contacts") {
                let name = DSJSON.string(c, "name")
                let parsed = (c["wire"] as? DSObj).flatMap(SyncContact.from)?.json()
                let expected = c["json"] as? DSObj ?? [:]
                out.check("record.contact.\(name)", parsed.map { NSDictionary(dictionary: $0).isEqual(to: expected) } ?? false,
                          "\(parsed.map { String(describing: $0) } ?? "nil")")
            }
            for g in DSJSON.objs(rec, "groups") {
                let name = DSJSON.string(g, "name")
                let parsed = (g["wire"] as? DSObj).flatMap(SyncGroup.from)
                out.check("record.group.\(name)", parsed?.canonicalJson() == DSJSON.string(g, "canonical"), parsed?.canonicalJson() ?? "nil")
                out.check("record.group.\(name).roundTrip", parsed.flatMap { SyncGroup.from($0.json()) }?.canonicalJson() == DSJSON.string(g, "canonical"))
            }
        } else { out.check("records.present", false) }
    }

    // ------------------------------------------------------------------ in-process loopback (2 engines)

    final class MemStore: DevSyncStore {
        var msgs: [String: [SyncMessage]] = [:]
        var convs: [String: SyncConversation] = [:]
        var media: [String: Data] = [:]
        var attached: [String: String] = [:]
        var grps: [String: SyncGroup] = [:]
        var ctcs: [String: SyncContact] = [:]
        var prof: SyncProfile?
        var reads: [String: SyncReadState] = [:]
        let lock = NSLock()
        func add(_ c: SyncConversation, _ m: SyncMessage) { lock.lock(); convs[c.id] = c; msgs[c.id, default: []].append(m); lock.unlock() }
        func conversations() -> [SyncConversation] { lock.lock(); defer { lock.unlock() }; return convs.values.filter { !(msgs[$0.id] ?? []).isEmpty } }
        func messages(_ c: String) -> [SyncMessage] { lock.lock(); defer { lock.unlock() }; return msgs[c] ?? [] }
        func messagesByIds(_ c: String, _ ids: [String]) -> [SyncMessage] {
            let want = Set(ids.map(DSIds.asciiLower))
            return messages(c).filter { want.contains($0.idKey) }
        }
        func apply(_ c: SyncConversation, inserts: [SyncMessage], updates: [SyncMessage]) -> DSApplyResult {
            lock.lock(); defer { lock.unlock() }
            if c.kind == SyncConversation.kindGroup, grps[c.groupId ?? ""] == nil { return DSApplyResult(skippedNoGroup: inserts.count + updates.count) }
            convs[c.id] = c
            var list = msgs[c.id] ?? []
            for u in updates { if let i = list.firstIndex(where: { $0.idKey == u.idKey }) { list[i] = u } }
            list.append(contentsOf: inserts)
            msgs[c.id] = list
            return DSApplyResult(inserted: inserts.count, updated: updates.count)
        }
        func openMedia(_ sha: String) -> Data? { lock.lock(); defer { lock.unlock() }; return media[sha] }
        func attachMedia(conversationId: String, messageId: String, sha256: String, file: URL) -> Bool {
            guard let d = try? Data(contentsOf: file) else { return false }
            lock.lock(); media[sha256] = d; attached[messageId] = sha256; lock.unlock()
            return true
        }
        func contacts() -> [SyncContact] { lock.lock(); defer { lock.unlock() }; return Array(ctcs.values) }
        func putContact(_ c: SyncContact) { lock.lock(); ctcs[c.publicKey] = c; lock.unlock() }
        func groups() -> [SyncGroup] { lock.lock(); defer { lock.unlock() }; return Array(grps.values) }
        func putGroup(_ g: SyncGroup) { lock.lock(); grps[g.groupId] = g; lock.unlock() }
        func profile() -> SyncProfile? { prof }
        func putProfile(_ p: SyncProfile) { prof = p }
        func readStates() -> [SyncReadState] { lock.lock(); defer { lock.unlock() }; return Array(reads.values) }
        func putReadState(_ r: SyncReadState) { lock.lock(); reads[r.conv] = r; lock.unlock() }
        func invalidate() {}
    }

    final class PipeLink: DSLink {
        let transport: String
        let label: String
        weak var peerEngine: DSEngine?
        var peerSession: (() -> DSSession?)?
        var closed = false
        init(transport: String, label: String) { self.transport = transport; self.label = label }
        func send(kind: Int, _ noise: Data) {
            guard !closed, let e = peerEngine else { return }
            let get = peerSession
            // LAN framing is exercised on the way through.
            let framed = DSWire.lanFrame(noise)
            e.post { get?()?.onFrame(kind: 0, Data(framed.dropFirst(2))) }
        }
        func close() { closed = true }
    }

    final class Collector: DSEngineDelegate {
        let lock = NSLock()
        var requests: [DSPairingRequest] = []
        var synced = 0
        var alerts: [String] = []
        weak var engine: DSEngine?
        var autoApprove = false
        func devsyncDevicesChanged() {}
        func devsyncPairingRequest(_ r: DSPairingRequest) {
            lock.lock(); requests.append(r); lock.unlock()
            if autoApprove, !r.thisDeviceIsNew || r.peerApproved { engine?.approve(r.peerId, allow: true) }
        }
        func devsyncPairingResolved(peerId: String, linked: Bool) {}
        func devsyncSessionChanged(_ i: DSSessionInfo) {}
        func devsyncApplied(peerId: String, conversationId: String, result: DSApplyResult) {}
        func devsyncSynced(peerId: String, stats: DSSessionStats) { lock.lock(); synced += 1; lock.unlock() }
        func devsyncAlert(kind: String, deviceName: String) { lock.lock(); alerts.append(kind); lock.unlock() }
    }

    private static func loopback(_ out: Out) {
        let ik = Data((1...32).map { UInt8($0) })
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("devsync-selftest-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: tmp) }
        let me = "BYYEjmVAaT4GhJvFyH3pqIojV6Z4NiKTxiq4Dp8V5Ug="
        let bob = "3b6TE6T6dfqOHzhG5WVPCdhuzDHQXrKCFxVoTqnlPUE="
        let conv = SyncConversation.direct(bob)
        let gid = "5E2A9C1B-7D3F-4B8A-9E6C-0F1D2A3B4C5D"
        let gconv = SyncConversation.group(gid, name: "Team")
        let sA = MemStore(), sB = MemStore()
        let pic = Data((0..<200_000).map { UInt8(truncatingIfNeeded: $0 &* 7) })
        let picSha = DSCrypto.hex(DSCrypto.sha256(pic))
        sA.media[picSha] = pic
        for i in 0..<300 {
            sA.add(conv, SyncMessage(id: "A-\(i)", conversation: conv.id, outgoing: i % 2 == 0, senderPublicKey: i % 2 == 0 ? me : bob,
                                     timestampMs: 1_758_000_000_000 + Int64(i) * 60_000, text: "from A #\(i)", status: "delivered", read: true))
        }
        sA.add(conv, SyncMessage(id: "A-pic", conversation: conv.id, outgoing: false, senderPublicKey: bob, timestampMs: 1_758_100_000_000,
                                 status: "delivered", read: false,
                                 media: SyncMedia(type: "image", fileName: "p.jpg", mime: "image/jpeg", size: Int64(pic.count), sha256: picSha)))
        sA.add(conv, SyncMessage(id: "shared-1", conversation: conv.id, outgoing: true, senderPublicKey: me, timestampMs: 1_758_000_500_000,
                                 text: "same", status: "sent", read: true))
        sB.add(conv, SyncMessage(id: "SHARED-1", conversation: conv.id, outgoing: true, senderPublicKey: me, timestampMs: 1_758_000_500_000,
                                 text: "same", status: "read", read: true, reactions: ["👍": [bob]]))
        sB.add(conv, SyncMessage(id: "B-only", conversation: conv.id, outgoing: false, senderPublicKey: bob, timestampMs: 1_758_200_000_000,
                                 text: "arrived while the phone was off", status: "delivered", read: false))
        sB.putGroup(SyncGroup(groupId: gid, name: "Team", members: [me, bob], admins: [me], epoch: 2,
                              keys: [SyncGroupKey(kind: "legacy-aes", version: 0, key: "a2V5")], updatedAtMs: 1_758_000_000_000))
        sB.add(gconv, SyncMessage(id: "B7E1C1B2-0000-4000-8000-000000000001", conversation: gconv.id, outgoing: false, senderPublicKey: bob,
                                  timestampMs: 1_758_300_000_000, text: "group hi", status: "delivered", read: true))
        sB.putContact(SyncContact(publicKey: bob, alias: "Bobby", aliasAtMs: 1_758_000_000_000, blocked: true, blockedAtMs: 1_758_000_000_000))

        func cfg(_ name: String) -> DSConfig {
            var c = DSConfig(deviceName: name, platform: "ios", appVersion: "selftest", cacheDir: tmp.appendingPathComponent(name))
            c.windowRecords = 32
            return c
        }
        let dkB = Curve25519.KeyAgreement.PrivateKey().rawRepresentation
        guard let eA = try? DSEngine(config: cfg("A"), accountPriv: ik, deviceKeyPriv: Curve25519.KeyAgreement.PrivateKey().rawRepresentation,
                                     store: sA, state: DSMemoryStateStore(), label: "selftest.A"),
              let eB = try? DSEngine(config: cfg("B"), accountPriv: ik, deviceKeyPriv: dkB,
                                     store: sB, state: DSMemoryStateStore(), label: "selftest.B") else { out.check("loopback.engines", false); return }
        let cA = Collector(), cB = Collector()
        cA.engine = eA; cB.engine = eB
        cA.autoApprove = true
        eA.delegate = cA; eB.delegate = cB
        eA.start(); eB.start()
        // A dials B over a LAN-like pipe.
        let lA = PipeLink(transport: DSKeys.transportLAN, label: "A->B"), lB = PipeLink(transport: DSKeys.transportLAN, label: "B<-A")
        var sessA: DSSession?, sessB: DSSession?
        lA.peerEngine = eB; lA.peerSession = { sessB }
        lB.peerEngine = eA; lB.peerSession = { sessA }
        let ready = DispatchSemaphore(value: 0)
        eB.post { sessB = eB.newSession(lB, initiator: false, expectedPeerId: nil); ready.signal() }
        ready.wait()
        eA.post { sessA = eA.newSession(lA, initiator: true, expectedPeerId: nil) }
        // Both are fresh: A's user taps Allow (autoApprove on A once B asks is not needed — both new).
        func waitUntil(_ secs: Double, _ cond: () -> Bool) -> Bool {
            let end = Date().addingTimeInterval(secs)
            while Date() < end { if cond() { return true }; Thread.sleep(forTimeInterval: 0.02) }
            return cond()
        }
        _ = waitUntil(10) {
            cA.lock.lock(); cB.lock.lock(); defer { cB.lock.unlock(); cA.lock.unlock() }
            return !cA.requests.isEmpty && !cB.requests.isEmpty
        }
        cA.lock.lock(); cB.lock.lock()
        let codeA = cA.requests.first?.code, codeB = cB.requests.first?.code
        cB.lock.unlock(); cA.lock.unlock()
        out.check("loopback.sasMatches", codeA != nil && codeA == codeB, "\(codeA ?? "-") vs \(codeB ?? "-")")
        eA.approve(eB.deviceId, allow: true)     // user on A taps Allow; B (fresh) accepts A's approval as consent
        let ok = waitUntil(20) {
            sB.messages(conv.id).count == 303 && sA.messages(conv.id).count == 303 && sB.attached["A-pic"] != nil
                && sA.messages(gconv.id).count == 1
        }
        out.check("loopback.converged", ok, "A=\(sA.messages(conv.id).count) B=\(sB.messages(conv.id).count) media=\(sB.attached["A-pic"] != nil) g=\(sA.messages(gconv.id).count)")
        out.check("loopback.linkedBothWays", eA.registry.isLinked(eB.deviceId) && eB.registry.isLinked(eA.deviceId))
        let digA = SyncCodec.digest(sA.messages(conv.id).map { ($0.id, SyncCodec.rev($0)) })
        let digB = SyncCodec.digest(sB.messages(conv.id).map { ($0.id, SyncCodec.rev($0)) })
        out.check("loopback.digestsEqual", digA == digB)
        out.check("loopback.mergedShared", sA.messagesByIds(conv.id, ["shared-1"]).first?.status == "read"
                  && sA.messagesByIds(conv.id, ["shared-1"]).first?.reactions["👍"] == [bob])
        out.check("loopback.mediaVerified", sB.media[picSha] == pic)
        out.check("loopback.groupsKeysSynced", sA.grps[gid]?.keys.first?.key == "a2V5" && sA.grps[gid]?.members.count == 2)
        out.check("loopback.contactBlockSynced", sA.ctcs[bob]?.blocked == true && sA.ctcs[bob]?.alias == "Bobby")
        out.check("loopback.readAsReceived", sA.messagesByIds(conv.id, ["B-only"]).first?.read == false)
        // LIVE: a new message on B reaches A inside the open session.
        let live = SyncMessage(id: "live-1", conversation: conv.id, outgoing: true, senderPublicKey: me, timestampMs: 1_758_400_000_000,
                               text: "live", status: "sent", read: true)
        sB.add(conv, live)
        eB.onLocalMessages(conv, [live])
        out.check("loopback.live", waitUntil(5) { !sA.messagesByIds(conv.id, ["live-1"]).isEmpty })
        // Credit window respected.
        let infos = eA.sessionsInfo()
        out.check("loopback.window", (infos.first?.stats.maxUnackedObserved ?? 99) <= 32, "\(infos.first?.stats.maxUnackedObserved ?? -1)")
        // Re-link: B keeps its device key but lost its list (reinstall; the Keychain survives).
        // A still trusts B: its DEVICES counts as approval on B, B's APPROVAL is echoed — no prompt.
        eB.stop()
        _ = waitUntil(3) { eA.sessionsInfo().isEmpty }
        guard let eB2 = try? DSEngine(config: cfg("B2"), accountPriv: ik, deviceKeyPriv: dkB, store: sB, state: DSMemoryStateStore(),
                                      label: "selftest.B2") else { out.check("loopback.relink.engine", false); return }
        let cB2 = Collector()
        eB2.delegate = cB2
        eB2.start()
        let rel = SyncMessage(id: "after-relink", conversation: conv.id, outgoing: true, senderPublicKey: me, timestampMs: 1_758_500_000_000,
                              text: "after relink", status: "sent", read: true)
        sA.add(conv, rel)
        let l2A = PipeLink(transport: DSKeys.transportLAN, label: "A->B2"), l2B = PipeLink(transport: DSKeys.transportLAN, label: "B2<-A")
        var s2A: DSSession?, s2B: DSSession?
        l2A.peerEngine = eB2; l2A.peerSession = { s2B }
        l2B.peerEngine = eA; l2B.peerSession = { s2A }
        let ready2 = DispatchSemaphore(value: 0)
        eB2.post { s2B = eB2.newSession(l2B, initiator: false, expectedPeerId: nil); ready2.signal() }
        ready2.wait()
        eA.post { s2A = eA.newSession(l2A, initiator: true, expectedPeerId: nil) }
        out.check("loopback.relinkWithoutPrompt", waitUntil(10) { !sB.messagesByIds(conv.id, ["after-relink"]).isEmpty && eB2.registry.isLinked(eA.deviceId) },
                  "B2 linked=\(eB2.registry.isLinked(eA.deviceId))")
        // Revoke: B removes A; A is refused on the next session.
        eB2.revoke(eA.deviceId)
        _ = waitUntil(3) { eA.sessionsInfo().isEmpty }
        out.check("loopback.revoked", eB2.registry.isRevoked(eA.deviceId) && !eB2.registry.isLinked(eA.deviceId))
        eA.stop(); eB2.stop()
        Thread.sleep(forTimeInterval: 0.1)
    }
}
// ===DEVSYNC-VECTORS-END===
