//
//  StegoV1.swift
//  OSHI — "Disguise as photo" (STEGO-v1). Normative spec: docs/stego/STEGO-v1.md
//
//  Byte-for-byte the same format as Android `service/stego/StegoV1.kt`; both run
//  docs/fixtures/stego/vectors.json and reveal each other's PNG. Crypto is the
//  existing OSHI primitive set (CryptoKit X25519, HKDF-SHA256, HMAC-SHA256,
//  AES-256-GCM); only their composition is fixed by the spec.
//
//  Foundation + CryptoKit + Compression only (no UIKit), so the same file is
//  compiled by the cross-platform swiftc harness (tools/stego-harness).
//

import Foundation
import CryptoKit
import Compression

nonisolated enum StegoV1 {
    static let headerPlain = 10
    static let headerSealed = headerPlain + 16
    static let prefix = 32 + headerSealed        // E ‖ HEADER = 58
    static let overhead = prefix + 16            // + body tag = 74
    static let maxBody = 1 << 20
    static let maxFile = 256 * 1024
    static let maxSlots = 3 * 4096 * 4096
    private static let magic: [UInt8] = Array("OSG1".utf8)
    private static let zeroNonce = try! AES.GCM.Nonce(data: Data(count: 12))

    /// 8-bit RGB samples, row-major, 3 bytes per pixel.
    struct RgbImage {
        let width: Int
        let height: Int
        var rgb: [UInt8]
        var slots: Int { rgb.count }
        init(width: Int, height: Int, rgb: [UInt8]) {
            precondition(width > 0 && height > 0 && rgb.count == width * height * 3)
            self.width = width; self.height = height; self.rgb = rgb
        }
    }

    struct Attachment: Equatable { let name: String; let mime: String; let data: Data }
    struct Payload: Equatable { let text: String; let createdAtMs: Int64; let file: Attachment? }

    enum RevealResult: Equatable {
        case found(sender: Data, payload: Payload)
        /// The header opened for one of our contacts but the body did not (image altered).
        case damaged(sender: Data)
        case notForYou
    }

    enum Failure: Error, Equatable {
        case capacity(needed: Int, available: Int)
        case tooLarge
        case invalidKey
    }

    static func capacityBytes(slots: Int) -> Int { max(0, slots / 8 - overhead) }

    // MARK: Randomness

    /// One byte at a time: direction / fill bits (spec §5).
    typealias ByteSource = () -> UInt8

    static func secureByteSource() -> ByteSource {
        var buf = [UInt8](repeating: 0, count: 4096)
        var pos = buf.count
        return {
            if pos == buf.count {
                _ = SecRandomCopyBytes(kSecRandomDefault, buf.count, &buf); pos = 0
            }
            defer { pos += 1 }
            return buf[pos]
        }
    }

    /// HMAC(key, u64be(k)) counter stream — the slot order and the test RNG.
    final class HmacStream {
        private let key: SymmetricKey
        private var counter: UInt64 = 0
        private var block: [UInt8] = []
        private var pos = 0
        init(key: Data) { self.key = SymmetricKey(data: key) }
        func nextByte() -> UInt8 {
            if pos == block.count {
                var be = counter.bigEndian
                let msg = withUnsafeBytes(of: &be) { Data($0) }
                block = Array(HMAC<SHA256>.authenticationCode(for: msg, using: key)); pos = 0; counter += 1
            }
            defer { pos += 1 }
            return block[pos]
        }
        func nextU32() -> UInt32 {
            (UInt32(nextByte()) << 24) | (UInt32(nextByte()) << 16) | (UInt32(nextByte()) << 8) | UInt32(nextByte())
        }
    }

    static func testByteSource() -> ByteSource {
        let s = HmacStream(key: Data("OSHI-STEGO-v1 test rng".utf8))
        return { s.nextByte() }
    }

    // MARK: Keys (spec §3)

    static func dh(_ priv: Data, _ pub: Data) throws -> Data {
        let k = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: priv)
        let p = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: pub)
        return try k.sharedSecretFromKeyAgreement(with: p).withUnsafeBytes { Data($0) }
    }

    static func publicKey(_ priv: Data) throws -> Data {
        try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: priv).publicKey.rawRepresentation
    }

    static func hkdf(_ ikm: Data, salt: Data, info: String) -> Data {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: salt, info: Data(info.utf8), outputByteCount: 32)
            .withUnsafeBytes { Data($0) }
    }

    static func orderKey(ssStatic: Data) -> Data { hkdf(ssStatic, salt: Data("OSHI-STEGO-v1".utf8), info: "order") }

    static func sealKeys(ssEph: Data, ssStatic: Data, e: Data, s: Data, r: Data) -> (header: Data, body: Data) {
        let ikm = ssEph + ssStatic, salt = e + s + r
        return (hkdf(ikm, salt: salt, info: "OSHI-STEGO-v1 header"), hkdf(ikm, salt: salt, info: "OSHI-STEGO-v1 body"))
    }

    private static func seal(_ key: Data, _ pt: Data, aad: Data) throws -> Data {
        let box = try AES.GCM.seal(pt, using: SymmetricKey(data: key), nonce: zeroNonce, authenticating: aad)
        return box.ciphertext + box.tag
    }

    private static func open(_ key: Data, _ ct: Data, aad: Data) throws -> Data {
        guard ct.count >= 16 else { throw Failure.tooLarge }
        let box = try AES.GCM.SealedBox(nonce: zeroNonce, ciphertext: ct.prefix(ct.count - 16), tag: ct.suffix(16))
        return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
    }

    // MARK: Slot order (spec §5)

    /// Lazily produces the keyed Fisher–Yates order over [0, n): sparse while few
    /// outputs are needed (the header trial reads 464), dense once the body is long.
    final class SlotOrder {
        private let stream: HmacStream
        private let n: Int
        private var t = 0
        private var sparse: [Int: Int]? = [:]
        private var dense: [Int32]? = nil

        init(orderKey: Data, n: Int) { stream = HmacStream(key: orderKey); self.n = n }

        private func rand(_ m: Int) -> Int {
            let range: UInt64 = 1 << 32
            let limit = range - (range % UInt64(m))
            while true {
                let w = UInt64(stream.nextU32())
                if w < limit { return Int(w % UInt64(m)) }
            }
        }
        private func get(_ i: Int) -> Int { dense != nil ? Int(dense![i]) : (sparse![i] ?? i) }
        private func set(_ i: Int, _ v: Int) { if dense != nil { dense![i] = Int32(v) } else { sparse![i] = v } }

        func ensureDense() {
            guard dense == nil else { return }
            var d = [Int32](unsafeUninitializedCapacity: n) { buf, count in
                for i in 0..<n { buf[i] = Int32(i) }
                count = n
            }
            for (k, v) in sparse! { d[k] = Int32(v) }
            dense = d; sparse = nil
        }

        func next() -> Int {
            precondition(t < n, "slot order exhausted")
            let j = t + rand(n - t)
            let at = get(t), aj = get(j)
            set(t, aj); set(j, at)
            defer { t += 1 }
            return get(t)
        }
    }

    // MARK: Payload encoding (spec §4)

    static func encodePayload(_ p: Payload) throws -> Data {
        var out = Data()
        func u8(_ v: Int) { out.append(UInt8(truncatingIfNeeded: v)) }
        func u16(_ v: Int) throws { guard v <= 0xFFFF else { throw Failure.tooLarge }; u8(v >> 8); u8(v) }
        func u32(_ v: Int) { u8(v >> 24); u8(v >> 16); u8(v >> 8); u8(v) }
        u8(1)
        for i in stride(from: 56, through: 0, by: -8) { u8(Int((p.createdAtMs >> Int64(i)) & 0xFF)) }
        let text = Data(p.text.utf8); u32(text.count); out.append(text)
        if let f = p.file {
            guard f.data.count <= maxFile else { throw Failure.tooLarge }
            u8(1)
            let name = Data(f.name.utf8); try u16(name.count); out.append(name)
            let mime = Data(f.mime.utf8); try u16(mime.count); out.append(mime)
            u32(f.data.count); out.append(f.data)
        } else { u8(0) }
        guard out.count <= maxBody else { throw Failure.tooLarge }
        return out
    }

    static func decodePayload(_ d: Data) throws -> Payload {
        let b = [UInt8](d); var i = 0
        func take(_ n: Int) throws -> [UInt8] {
            guard n >= 0, i + n <= b.count else { throw Failure.tooLarge }
            defer { i += n }; return Array(b[i..<(i + n)])
        }
        func uint(_ n: Int) throws -> Int { try take(n).reduce(0) { ($0 << 8) | Int($1) } }
        guard try uint(1) == 1 else { throw Failure.tooLarge }
        let created = Int64(bitPattern: UInt64(try take(8).reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }))
        guard let text = String(bytes: try take(try uint(4)), encoding: .utf8) else { throw Failure.tooLarge }
        var file: Attachment? = nil
        switch try uint(1) {
        case 0: break
        case 1:
            guard let name = String(bytes: try take(try uint(2)), encoding: .utf8),
                  let mime = String(bytes: try take(try uint(2)), encoding: .utf8) else { throw Failure.tooLarge }
            file = Attachment(name: name, mime: mime, data: Data(try take(try uint(4))))
        default: throw Failure.tooLarge
        }
        guard i == b.count else { throw Failure.tooLarge }
        return Payload(text: text, createdAtMs: created, file: file)
    }

    // MARK: Hide

    static func hide(cover: RgbImage, payload: Payload, senderPriv: Data, senderPub: Data, recipientPub: Data,
                     ephemeralPriv: Data? = nil, random: ByteSource = secureByteSource()) throws -> RgbImage {
        let body = try encodePayload(payload)
        let available = capacityBytes(slots: cover.slots)
        guard body.count <= available else { throw Failure.capacity(needed: body.count, available: available) }
        guard recipientPub.count == 32 else { throw Failure.invalidKey }

        let ePriv = ephemeralPriv ?? Curve25519.KeyAgreement.PrivateKey().rawRepresentation
        let ePub = try publicKey(ePriv)
        let ssStatic = try dh(senderPriv, recipientPub)
        let ssEph = try dh(ePriv, recipientPub)
        let keys = sealKeys(ssEph: ssEph, ssStatic: ssStatic, e: ePub, s: senderPub, r: recipientPub)
        var headerPlain = Data(magic) + Data([1, 0])
        let n = UInt32(body.count)
        headerPlain += Data([UInt8(n >> 24), UInt8((n >> 16) & 255), UInt8((n >> 8) & 255), UInt8(n & 255)])
        let header = try seal(keys.header, headerPlain, aad: ePub)
        let sealedBody = try seal(keys.body, body, aad: ePub + header)
        let stream = [UInt8](ePub + header + sealedBody)

        var out = cover.rgb
        var used = [Bool](repeating: false, count: cover.slots)
        let order = SlotOrder(orderKey: orderKey(ssStatic: ssStatic), n: cover.slots)
        if stream.count * 8 > 50_000 { order.ensureDense() }
        for v in stream {
            for bit in stride(from: 7, through: 0, by: -1) {
                let slot = order.next()
                used[slot] = true
                embed(&out, slot, Int(v >> UInt8(bit)) & 1, Int(random()) & 1)
            }
        }
        for slot in 0..<cover.slots where !used[slot] {
            let r = Int(random())
            embed(&out, slot, (r >> 7) & 1, r & 1)
        }
        return RgbImage(width: cover.width, height: cover.height, rgb: out)
    }

    @inline(__always)
    private static func embed(_ samples: inout [UInt8], _ slot: Int, _ bit: Int, _ up: Int) {
        let v = Int(samples[slot])
        if (v & 1) == bit { return }
        let nv: Int
        switch v { case 0: nv = 1; case 255: nv = 254; default: nv = up == 1 ? v + 1 : v - 1 }
        samples[slot] = UInt8(nv)
    }

    // MARK: Reveal (spec §6)

    /// Tries each of `contacts` as the sender. Never throws: any failure is `.notForYou`.
    static func reveal(image: RgbImage, myPriv: Data, myPub: Data, contacts: [Data]) -> RevealResult {
        guard image.slots <= maxSlots, image.slots >= overhead * 8 else { return .notForYou }
        for sender in contacts where sender.count == 32 {
            if let result = try? tryContact(image, myPriv, myPub, sender) { return result }
        }
        return .notForYou
    }

    private static func readBytes(_ image: RgbImage, _ order: SlotOrder, _ count: Int) -> Data {
        var out = [UInt8](repeating: 0, count: count)
        for i in 0..<count {
            var b: UInt8 = 0
            for _ in 0..<8 { b = (b << 1) | (image.rgb[order.next()] & 1) }
            out[i] = b
        }
        return Data(out)
    }

    private static func tryContact(_ image: RgbImage, _ myPriv: Data, _ myPub: Data, _ sender: Data) throws -> RevealResult? {
        let ssStatic = try dh(myPriv, sender)
        let order = SlotOrder(orderKey: orderKey(ssStatic: ssStatic), n: image.slots)
        let pre = readBytes(image, order, prefix)
        let ePub = pre.prefix(32), header = pre.suffix(headerSealed)
        let ssEph = try dh(myPriv, Data(ePub))
        let keys = sealKeys(ssEph: ssEph, ssStatic: ssStatic, e: Data(ePub), s: sender, r: myPub)
        guard let h = try? [UInt8](open(keys.header, Data(header), aad: Data(ePub))) else { return nil }
        guard Array(h[0..<4]) == magic, h[4] == 1, h[5] == 0 else { return .damaged(sender: sender) }
        let bodyLen = h[6..<10].reduce(0) { ($0 << 8) | Int($1) }
        guard bodyLen <= maxBody, (prefix + bodyLen + 16) * 8 <= image.slots else { return .damaged(sender: sender) }
        if (bodyLen + 16) * 8 > 50_000 { order.ensureDense() }
        let sealedBody = readBytes(image, order, bodyLen + 16)
        guard let body = try? open(keys.body, sealedBody, aad: Data(ePub) + Data(header)),
              let payload = try? decodePayload(body) else { return .damaged(sender: sender) }
        return .found(sender: sender, payload: payload)
    }
}

// MARK: - PNG (spec §5)

/// Minimal PNG reader/writer. CGImage/UIImage decode through a colour space and
/// premultiplied alpha, either of which can move a sample by one — the whole
/// message. Reading samples ourselves is the only way Android and iOS see the
/// same bits; writing ourselves guarantees no ancillary (metadata) chunks.
nonisolated enum StegoPng {
    static let signature: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]
    enum Failure: Error { case unsupported(String) }

    static func isPng(_ d: Data) -> Bool { d.count >= 8 && Array(d.prefix(8)) == signature }

    /// Width and height as declared by IHDR, without decoding anything (nil if
    /// there is no well-formed IHDR first). Used to refuse, before any platform
    /// decode, a picture too large to carry a STEGO-v1 message.
    static func declaredSize(_ d: Data) -> (width: Int, height: Int)? {
        guard isPng(d), d.count >= 33 else { return nil }
        let b = [UInt8](d.prefix(33))
        guard b[12] == 0x49, b[13] == 0x48, b[14] == 0x44, b[15] == 0x52 else { return nil }
        func u32(_ o: Int) -> Int { (Int(b[o]) << 24) | (Int(b[o + 1]) << 16) | (Int(b[o + 2]) << 8) | Int(b[o + 3]) }
        return (u32(16), u32(20))
    }

    private static let crcTable: [UInt32] = (0..<256).map { n -> UInt32 in
        var c = UInt32(n)
        for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1 }
        return c
    }
    static func crc32(_ parts: [UInt8]...) -> UInt32 {
        var c: UInt32 = 0xFFFFFFFF
        for p in parts { for b in p { c = crcTable[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) } }
        return c ^ 0xFFFFFFFF
    }
    private static func adler32(_ d: [UInt8]) -> UInt32 {
        var a: UInt32 = 1, b: UInt32 = 0
        var i = 0
        while i < d.count {
            let end = min(i + 5552, d.count)
            while i < end { a += UInt32(d[i]); b += a; i += 1 }
            a %= 65521; b %= 65521
        }
        return (b << 16) | a
    }
    private static func be32(_ v: UInt32) -> [UInt8] { [UInt8(v >> 24), UInt8((v >> 16) & 255), UInt8((v >> 8) & 255), UInt8(v & 255)] }

    private static func paeth(_ a: Int, _ b: Int, _ c: Int) -> Int {
        let p = a + b - c, pa = abs(p - a), pb = abs(p - b), pc = abs(p - c)
        return (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c)
    }

    private static func process(_ algorithm: compression_algorithm, encode: Bool, _ input: [UInt8], capacity: Int) -> [UInt8]? {
        var dst = [UInt8](repeating: 0, count: max(capacity, 64))
        let n = input.withUnsafeBufferPointer { src in
            dst.withUnsafeMutableBufferPointer { d in
                encode ? compression_encode_buffer(d.baseAddress!, d.count, src.baseAddress!, src.count, nil, algorithm)
                       : compression_decode_buffer(d.baseAddress!, d.count, src.baseAddress!, src.count, nil, algorithm)
            }
        }
        guard n > 0 else { return nil }
        return Array(dst[0..<n])
    }

    static func encode(_ image: StegoV1.RgbImage) -> Data {
        let w = image.width, h = image.height, stride = w * 3
        var raw = [UInt8](); raw.reserveCapacity(h * (stride + 1))
        var prev = [UInt8](repeating: 0, count: stride)
        var cand = [[UInt8]](repeating: [UInt8](repeating: 0, count: stride), count: 5)
        image.rgb.withUnsafeBufferPointer { px in
            for y in 0..<h {
                let row = Array(px[(y * stride)..<((y + 1) * stride)])
                var best = 0, bestScore = Int.max
                for f in 0...4 {
                    var score = 0
                    for i in 0..<stride {
                        let x = Int(row[i]), a = i >= 3 ? Int(row[i - 3]) : 0
                        let b = y > 0 ? Int(prev[i]) : 0, c = (y > 0 && i >= 3) ? Int(prev[i - 3]) : 0
                        let v: Int
                        switch f { case 0: v = x; case 1: v = x - a; case 2: v = x - b; case 3: v = x - ((a + b) >> 1); default: v = x - paeth(a, b, c) }
                        let byte = UInt8(truncatingIfNeeded: v)
                        cand[f][i] = byte; score += abs(Int(Int8(bitPattern: byte)))
                    }
                    if score < bestScore { bestScore = score; best = f }
                }
                raw.append(UInt8(best)); raw.append(contentsOf: cand[best])
                prev = row
            }
        }
        let deflated = process(COMPRESSION_ZLIB, encode: true, raw, capacity: raw.count + raw.count / 10 + 1024) ?? []
        let zlib: [UInt8] = [0x78, 0x9C] + deflated + be32(adler32(raw))
        var out = signature
        func chunk(_ type: String, _ data: [UInt8]) {
            let t = Array(type.utf8)
            out += be32(UInt32(data.count)) + t + data + be32(crc32(t, data))
        }
        chunk("IHDR", be32(UInt32(w)) + be32(UInt32(h)) + [8, 2, 0, 0, 0])
        var off = 0
        while off < zlib.count { let n = min(1 << 16, zlib.count - off); chunk("IDAT", Array(zlib[off..<(off + n)])); off += n }
        chunk("IEND", [])
        return Data(out)
    }

    /// Exact 8-bit samples of a non-interlaced 8-bit PNG, as RGB (alpha dropped).
    static func decode(_ data: Data) throws -> StegoV1.RgbImage {
        guard isPng(data) else { throw Failure.unsupported("not a PNG") }
        let b = [UInt8](data)
        var i = 8, w = 0, h = 0, colorType = -1
        var palette: [UInt8]? = nil
        var idat = [UInt8]()
        func u32(_ o: Int) -> Int { (Int(b[o]) << 24) | (Int(b[o + 1]) << 16) | (Int(b[o + 2]) << 8) | Int(b[o + 3]) }
        while i + 12 <= b.count {
            let len = u32(i)
            guard len >= 0, i + 12 + len <= b.count else { throw Failure.unsupported("truncated chunk") }
            let type = String(bytes: b[(i + 4)..<(i + 8)], encoding: .ascii) ?? ""
            let d = Array(b[(i + 8)..<(i + 8 + len)])
            i += 12 + len
            if type == "IHDR" {
                guard d.count >= 13 else { throw Failure.unsupported("IHDR") }
                w = (Int(d[0]) << 24) | (Int(d[1]) << 16) | (Int(d[2]) << 8) | Int(d[3])
                h = (Int(d[4]) << 24) | (Int(d[5]) << 16) | (Int(d[6]) << 8) | Int(d[7])
                guard d[8] == 8 else { throw Failure.unsupported("bit depth") }
                colorType = Int(d[9])
                guard d[12] == 0 else { throw Failure.unsupported("interlaced") }
                guard [0, 2, 3, 4, 6].contains(colorType) else { throw Failure.unsupported("colour type") }
                // __STEGO_DAMAGED_PNG_2026_11_18__ Each side is bounded BEFORE the product:
                // IHDR holds two u32s, and 0xFFFFFFFF x 0xFFFFFFFF overflows Int — a trap,
                // i.e. opening a hostile "photo" crashed the app (netem/stego probe, Simulator).
                guard w > 0, h > 0, w <= 4096 * 4096, h <= 4096 * 4096, w * h <= 4096 * 4096 else { throw Failure.unsupported("size") }
            } else if type == "PLTE" { palette = d }
            else if type == "IDAT" { idat += d }
            else if type == "IEND" { break }
        }
        guard w > 0, idat.count > 2 else { throw Failure.unsupported("no image") }
        let channels = [0: 1, 2: 3, 3: 1, 4: 2, 6: 4][colorType]!
        let stride = w * channels
        let expected = (stride + 1) * h
        // zlib stream → raw DEFLATE for the Compression framework (skip the 2-byte header).
        guard let raw = process(COMPRESSION_ZLIB, encode: false, Array(idat.dropFirst(2)), capacity: expected + 1),
              raw.count >= expected else { throw Failure.unsupported("short image data") }
        var rgb = [UInt8](repeating: 0, count: w * h * 3)
        var prev = [UInt8](repeating: 0, count: stride), cur = [UInt8](repeating: 0, count: stride)
        for y in 0..<h {
            let base = y * (stride + 1), f = raw[base]
            for k in 0..<stride {
                let x = Int(raw[base + 1 + k]), a = k >= channels ? Int(cur[k - channels]) : 0
                let bb = y > 0 ? Int(prev[k]) : 0, c = (y > 0 && k >= channels) ? Int(prev[k - channels]) : 0
                let v: Int
                switch f {
                case 0: v = x; case 1: v = x + a; case 2: v = x + bb; case 3: v = x + ((a + bb) >> 1); case 4: v = x + paeth(a, bb, c)
                default: throw Failure.unsupported("filter")
                }
                cur[k] = UInt8(truncatingIfNeeded: v)
            }
            for px in 0..<w {
                let o = (y * w + px) * 3
                switch colorType {
                case 0, 4: let g = cur[px * channels]; rgb[o] = g; rgb[o + 1] = g; rgb[o + 2] = g
                case 3:
                    guard let p = palette else { throw Failure.unsupported("palette") }
                    let idx = Int(cur[px]) * 3
                    guard idx + 2 < p.count else { throw Failure.unsupported("palette index") }
                    rgb[o] = p[idx]; rgb[o + 1] = p[idx + 1]; rgb[o + 2] = p[idx + 2]
                default: rgb[o] = cur[px * channels]; rgb[o + 1] = cur[px * channels + 1]; rgb[o + 2] = cur[px * channels + 2]
                }
            }
            prev = cur
        }
        return StegoV1.RgbImage(width: w, height: h, rgb: rgb)
    }
}
