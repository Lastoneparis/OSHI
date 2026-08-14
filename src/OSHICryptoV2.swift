//
//  OSHICryptoV2.swift
//  OSHI — v2 end-to-end-encryption core (Swift / Apple CryptoKit port).
//
//  This is a BYTE-FOR-BYTE port of the canonical Node.js reference
//  `crypto_ref.js` (OSHI v2). It exists so the iOS client and the server-side
//  reference derive identical keys / ciphertexts. Every construction below
//  mirrors the reference; see the block comments for the exact mapping.
//
//  ⚠️ BYTE-PARITY MUST BE CONFIRMED BY RUNNING `OSHICryptoV2Tests` ON A MAC.
//  This file was written and reviewed against `crypto_ref.js` in an environment
//  with NO iOS/Swift runtime, so it could not be executed here. The test target
//  asserts the fixed reference vectors (X3DH SK, one ratchet message key +
//  ciphertext, one file chunk) plus self-consistency round-trips.
//
//  Namespacing: EVERYTHING lives inside `enum OSHICryptoV2` (nested types +
//  static funcs) to avoid clashing with the existing (separate) DoubleRatchet /
//  IdentityManager types already in this module. Nothing here defines a
//  top-level type named DoubleRatchet, IdentityManager, etc.
//
//  Primitive mapping (reference -> CryptoKit):
//    * X25519  ECDH        -> Curve25519.KeyAgreement
//    * Ed25519 signatures  -> Curve25519.Signing
//    * HKDF-SHA256         -> HKDF<SHA256>
//    * HMAC-SHA256         -> HMAC<SHA256>
//    * AES-256-GCM         -> AES.GCM  (ciphertext layout: ct || tag)
//
//  Known / intentional CryptoKit deviations (documented, none affect the tested
//  vectors):
//    (1) Ed25519 `sign` — CryptoKit's Curve25519.Signing produces RANDOMIZED
//        signatures (not byte-identical to Node's deterministic RFC-8032 output).
//        `verify` interoperates in both directions, and there is no signature
//        test vector, so this does not break parity. Only the signed-prekey
//        VERIFY path is security-relevant.
//    (2) X25519 shared secret — extracted from CryptoKit's `SharedSecret` via
//        `withUnsafeBytes`; this is the raw 32-byte X25519 output, identical to
//        Node's `crypto.diffieHellman` result.
//    (3) HKDF salt — CryptoKit's `HKDF.deriveKey` takes an explicit `salt`
//        (DataProtocol). We ALWAYS pass an explicit 32-byte salt (0x00*32) or the
//        root key `rk`, never empty, so there is no empty-salt ambiguity between
//        implementations.
//    (4) File manifest JSON — built manually to reproduce Node `JSON.stringify`
//        key ordering (filename, mime, size, chunkCount, chunkSize) and compact
//        (no-whitespace) formatting. There is no manifest test vector; this only
//        matters for cross-language manifest interop.
//

import Foundation
import CryptoKit

enum OSHICryptoV2 {

    // MARK: - Errors

    enum CryptoError: Error {
        case ciphertextTooShort
        case tooManySkippedMessages
        case chunkCountMismatch
        case sizeMismatch
        case badState
    }

    // MARK: - Nested types

    /// A raw X25519 (key agreement) key pair. `priv`/`pub` are 32-byte raw values
    /// (CryptoKit rawRepresentation), mirroring the reference's { priv, pub }.
    struct X25519Pair {
        let priv: Data
        let pub: Data
    }

    /// A raw Ed25519 (signing) key pair. `priv` is the 32-byte seed
    /// (CryptoKit rawRepresentation), `pub` is the 32-byte public key.
    struct Ed25519Pair {
        let priv: Data
        let pub: Data
    }

    /// Double Ratchet message header. Wire encoding: dh(32) || pn(u32be) || n(u32be).
    struct Header {
        var dh: Data
        var pn: UInt32
        var n: UInt32
    }

    /// Mutable Double Ratchet session state (reference type; the reference mutates
    /// its state object in place). Field names mirror `crypto_ref.js`.
    final class RatchetState {
        var DHs: X25519Pair          // our current ratchet key pair
        var DHr: Data?               // their current ratchet public key
        var RK: Data                 // root key
        var CKs: Data?               // sending chain key
        var CKr: Data?               // receiving chain key
        var Ns: UInt32 = 0           // sending message number
        var Nr: UInt32 = 0           // receiving message number
        var PN: UInt32 = 0           // previous sending chain length
        var MKSKIPPED: [String: Data] = [:]  // skipped message keys

        init(DHs: X25519Pair, DHr: Data?, RK: Data, CKs: Data?, CKr: Data?) {
            self.DHs = DHs
            self.DHr = DHr
            self.RK = RK
            self.CKs = CKs
            self.CKr = CKr
        }
    }

    /// Result of `encryptFile`.
    struct EncryptedFile {
        let fileKey: Data            // 32 random bytes
        let fileNonce: Data          // 8 random bytes (base for every chunk nonce)
        let chunks: [Data]           // each = ct || tag
        let manifest: Data           // encrypted manifest blob (ct || tag)
        let infoJSON: Data           // plaintext manifest JSON (caller convenience)
    }

    /// Plaintext file metadata carried into the manifest.
    struct FileMeta {
        var filename: String
        var mime: String
        init(filename: String = "file.bin", mime: String = "application/octet-stream") {
            self.filename = filename
            self.mime = mime
        }
    }

    // ==========================================================================
    // SECTION 0 — Encoding helpers & raw conversions.
    // ==========================================================================

    /// base64url (Node `Buffer.toString('base64url')`): no padding, `-`/`_`.
    static func b64u(_ data: Data) -> String {
        var s = data.base64EncodedString()
        s = s.replacingOccurrences(of: "+", with: "-")
        s = s.replacingOccurrences(of: "/", with: "_")
        s = s.replacingOccurrences(of: "=", with: "")
        return s
    }

    /// Inverse of `b64u` (Node `Buffer.from(s, 'base64url')`).
    static func fromB64u(_ s: String) -> Data {
        var t = s.replacingOccurrences(of: "-", with: "+")
        t = t.replacingOccurrences(of: "_", with: "/")
        let rem = t.count % 4
        if rem > 0 { t += String(repeating: "=", count: 4 - rem) }
        return Data(base64Encoded: t) ?? Data()
    }

    /// 32-bit big-endian encoding (Node `writeUInt32BE`).
    static func u32be(_ n: UInt32) -> Data {
        var d = Data(count: 4)
        d[0] = UInt8((n >> 24) & 0xff)
        d[1] = UInt8((n >> 16) & 0xff)
        d[2] = UInt8((n >> 8) & 0xff)
        d[3] = UInt8(n & 0xff)
        return d
    }

    /// Constant-time equality (Node `crypto.timingSafeEqual` behaviour).
    static func ctEqual(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        let ab = [UInt8](a), bb = [UInt8](b)
        var diff: UInt8 = 0
        for i in 0..<ab.count { diff |= ab[i] ^ bb[i] }
        return diff == 0
    }

    /// Cryptographically secure random bytes (Foundation-only; uses the system's
    /// secure RNG). Mirrors Node `crypto.randomBytes`.
    static func randomBytes(_ count: Int) -> Data {
        var rng = SystemRandomNumberGenerator()
        var d = Data(count: count)
        for i in 0..<count { d[i] = UInt8.random(in: 0...255, using: &rng) }
        return d
    }

    // ==========================================================================
    // Primitives
    // ==========================================================================

    // ---- X25519 (ECDH) -------------------------------------------------------

    static func generateX25519() -> X25519Pair {
        let p = Curve25519.KeyAgreement.PrivateKey()
        return X25519Pair(priv: p.rawRepresentation, pub: p.publicKey.rawRepresentation)
    }

    /// Recover the public key raw bytes from a private key (mirrors
    /// PrivateKey.publicKey / the reference's `x25519Public`).
    static func x25519Public(_ pair: X25519Pair) throws -> Data {
        let priv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: pair.priv)
        return priv.publicKey.rawRepresentation
    }

    /// DH(my private, their public) -> raw 32-byte shared secret.
    static func dh(_ mine: X25519Pair, _ theirPub: Data) throws -> Data {
        let priv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: mine.priv)
        let pub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: theirPub)
        let ss = try priv.sharedSecretFromKeyAgreement(with: pub)
        return ss.withUnsafeBytes { Data($0) }
    }

    // ---- Ed25519 (signatures) ------------------------------------------------

    static func generateEd25519() -> Ed25519Pair {
        let p = Curve25519.Signing.PrivateKey()
        return Ed25519Pair(priv: p.rawRepresentation, pub: p.publicKey.rawRepresentation)
    }

    /// NOTE: CryptoKit Ed25519 signatures are randomized (see file header).
    static func sign(_ pair: Ed25519Pair, _ msg: Data) throws -> Data {
        let priv = try Curve25519.Signing.PrivateKey(rawRepresentation: pair.priv)
        return try priv.signature(for: msg)
    }

    static func verify(_ pubRaw: Data, _ msg: Data, _ sig: Data) -> Bool {
        guard let pub = try? Curve25519.Signing.PublicKey(rawRepresentation: pubRaw) else { return false }
        return pub.isValidSignature(sig, for: msg)
    }

    // ---- HKDF-SHA256 & HMAC-SHA256 ------------------------------------------

    static func hkdf(ikm: Data, salt: Data, info: Data, length: Int) -> Data {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm),
            salt: salt,
            info: info,
            outputByteCount: length
        )
        return key.withUnsafeBytes { Data($0) }
    }

    static func hmac(key: Data, data: Data) -> Data {
        let mac = HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: key))
        return Data(mac)
    }

    // ---- AES-256-GCM ---------------------------------------------------------
    // Ciphertext layout is `ct || tag` (16-byte tag appended); the 12-byte nonce
    // is carried explicitly. Empty AAD is equivalent to Node not calling setAAD.

    static let gcmTagLen = 16
    static let gcmNonceLen = 12

    static func aesGcmSeal(key: Data, nonce: Data, plaintext: Data, aad: Data) throws -> Data {
        let sealed = try AES.GCM.seal(
            plaintext,
            using: SymmetricKey(data: key),
            nonce: AES.GCM.Nonce(data: nonce),
            authenticating: aad
        )
        return sealed.ciphertext + sealed.tag
    }

    static func aesGcmOpen(key: Data, nonce: Data, ctAndTag: Data, aad: Data) throws -> Data {
        guard ctAndTag.count >= gcmTagLen else { throw CryptoError.ciphertextTooShort }
        let ct = Data(ctAndTag.prefix(ctAndTag.count - gcmTagLen))
        let tag = Data(ctAndTag.suffix(gcmTagLen))
        let box = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: nonce),
            ciphertext: ct,
            tag: tag
        )
        return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
    }

    // ==========================================================================
    // SECTION 1 — X3DH session setup.
    //
    //   SK = HKDF-SHA256( ikm = F || DH1||DH2||DH3||DH4,
    //                     salt = 0x00 * 32, info = "OSHI_X3DH", len = 32 )
    //   F = 0xFF * 32.
    //   DH1 = DH(IK_a, SPK_b), DH2 = DH(EK_a, IK_b),
    //   DH3 = DH(EK_a, SPK_b), DH4 = DH(EK_a, OPK_b)  (DH4 omitted if no OPK).
    // ==========================================================================

    static let x3dhInfo = Data("OSHI_X3DH".utf8)

    private static var x3dhF: Data { Data(repeating: 0xff, count: 32) }
    private static var zero32: Data { Data(repeating: 0x00, count: 32) }

    static func x3dhDeriveSK(_ dhs: [Data]) -> Data {
        var ikm = x3dhF
        for d in dhs { ikm.append(d) }
        return hkdf(ikm: ikm, salt: zero32, info: x3dhInfo, length: 32)
    }

    /// Alice's side. `theirOpkPub` may be nil (then DH4 is omitted).
    static func x3dhInitiator(ik: X25519Pair,
                              ek: X25519Pair,
                              theirIkPub: Data,
                              theirSpkPub: Data,
                              theirOpkPub: Data?) throws -> Data {
        var dhs: [Data] = [
            try dh(ik, theirSpkPub),  // DH1 = DH(IK_a, SPK_b)
            try dh(ek, theirIkPub),   // DH2 = DH(EK_a, IK_b)
            try dh(ek, theirSpkPub),  // DH3 = DH(EK_a, SPK_b)
        ]
        if let opk = theirOpkPub {
            dhs.append(try dh(ek, opk)) // DH4 = DH(EK_a, OPK_b)
        }
        return x3dhDeriveSK(dhs)
    }

    /// Bob's side — reconstructs the identical SK. `opk` may be nil (must match
    /// Alice omitting `theirOpkPub`).
    static func x3dhResponder(ik: X25519Pair,
                              spk: X25519Pair,
                              opk: X25519Pair?,
                              theirIkPub: Data,
                              theirEkPub: Data) throws -> Data {
        var dhs: [Data] = [
            try dh(spk, theirIkPub),  // == DH(IK_a, SPK_b)
            try dh(ik, theirEkPub),   // == DH(EK_a, IK_b)
            try dh(spk, theirEkPub),  // == DH(EK_a, SPK_b)
        ]
        if let opk = opk {
            dhs.append(try dh(opk, theirEkPub)) // == DH(EK_a, OPK_b)
        }
        return x3dhDeriveSK(dhs)
    }

    static func signPrekey(_ idSignPair: Ed25519Pair, _ spkPub: Data) throws -> Data {
        return try sign(idSignPair, spkPub)
    }

    static func verifyPrekey(_ idSignPubRaw: Data, _ spkPub: Data, _ signature: Data) -> Bool {
        return verify(idSignPubRaw, spkPub, signature)
    }

    // ==========================================================================
    // SECTION 2 — Double Ratchet (Signal), seeded from the X3DH SK.
    //
    //   KDF_RK(rk, dh_out): HKDF(ikm=dh_out, salt=rk, info="OSHI_DR_ROOT", 64)
    //                       -> (RK' = out[0:32], CK = out[32:64])
    //   KDF_CK(ck): mk = HMAC(ck, 0x01), ck_next = HMAC(ck, 0x02)
    //   MSG:        HKDF(ikm=mk, salt=0x00*32, info="OSHI_DR_MSG", 44)
    //               -> key = out[0:32], nonce = out[32:44]
    // ==========================================================================

    static let drRootInfo = Data("OSHI_DR_ROOT".utf8)
    static let drMsgInfo = Data("OSHI_DR_MSG".utf8)
    static let maxSkip = 1000

    static func kdfRK(_ rk: Data, _ dhOut: Data) -> (rk: Data, ck: Data) {
        let out = hkdf(ikm: dhOut, salt: rk, info: drRootInfo, length: 64)
        return (Data(out[0..<32]), Data(out[32..<64]))
    }

    /// Returns (nextCK, mk) — matching the reference's `[nextCK, mk]`.
    static func kdfCK(_ ck: Data) -> (nextCK: Data, mk: Data) {
        let mk = hmac(key: ck, data: Data([0x01]))
        let nextCK = hmac(key: ck, data: Data([0x02]))
        return (nextCK, mk)
    }

    static func deriveMsgKey(_ mk: Data) -> (key: Data, nonce: Data) {
        let out = hkdf(ikm: mk, salt: zero32, info: drMsgInfo, length: gcmNonceLen + 32)
        return (Data(out[0..<32]), Data(out[32..<(32 + gcmNonceLen)]))
    }

    /// Deterministic wire encoding: dh(32) || pn(u32be) || n(u32be).
    static func encodeHeader(_ header: Header) -> Data {
        var d = Data()
        d.append(header.dh)
        d.append(u32be(header.pn))
        d.append(u32be(header.n))
        return d
    }

    /// Skipped-key store key: ratchet-pub (base64url) + ":" + message number.
    static func skKey(_ dhPub: Data, _ n: UInt32) -> String {
        return b64u(dhPub) + ":" + String(n)
    }

    // ---- Initialization ------------------------------------------------------

    /// Alice = X3DH initiator. She knows Bob's ratchet public key and performs
    /// the first DH ratchet immediately, producing her sending chain.
    static func ratchetInitAlice(SK: Data, bobRatchetPub: Data) throws -> RatchetState {
        let DHs = generateX25519()
        let (RK, CKs) = kdfRK(SK, try dh(DHs, bobRatchetPub))
        return RatchetState(DHs: DHs, DHr: Data(bobRatchetPub), RK: RK, CKs: CKs, CKr: nil)
    }

    /// Bob = X3DH responder. His initial ratchet pair is the signed-prekey pair;
    /// root key = SK. No receiving chain yet.
    static func ratchetInitBob(SK: Data, bobRatchetPair: X25519Pair) -> RatchetState {
        return RatchetState(
            DHs: X25519Pair(priv: Data(bobRatchetPair.priv), pub: Data(bobRatchetPair.pub)),
            DHr: nil,
            RK: Data(SK),
            CKs: nil,
            CKr: nil
        )
    }

    // ---- Encrypt -------------------------------------------------------------

    static func ratchetEncrypt(_ state: RatchetState,
                               plaintext: Data,
                               associatedData: Data) throws -> (header: Header, ciphertext: Data) {
        guard let cks = state.CKs else { throw CryptoError.badState }
        let (ck, mk) = kdfCK(cks)
        state.CKs = ck
        let header = Header(dh: Data(state.DHs.pub), pn: state.PN, n: state.Ns)
        state.Ns += 1
        let (key, nonce) = deriveMsgKey(mk)
        let aad = associatedData + encodeHeader(header)
        let ciphertext = try aesGcmSeal(key: key, nonce: nonce, plaintext: plaintext, aad: aad)
        return (header, ciphertext)
    }

    // ---- Decrypt -------------------------------------------------------------

    private static func trySkipped(_ state: RatchetState,
                                   _ header: Header,
                                   _ ciphertext: Data,
                                   _ associatedData: Data) throws -> Data? {
        let k = skKey(header.dh, header.n)
        guard let mk = state.MKSKIPPED[k] else { return nil }
        state.MKSKIPPED.removeValue(forKey: k)
        return try openWithMk(mk, header, ciphertext, associatedData)
    }

    private static func openWithMk(_ mk: Data,
                                   _ header: Header,
                                   _ ciphertext: Data,
                                   _ associatedData: Data) throws -> Data {
        let (key, nonce) = deriveMsgKey(mk)
        let aad = associatedData + encodeHeader(header)
        return try aesGcmOpen(key: key, nonce: nonce, ctAndTag: ciphertext, aad: aad)
    }

    /// Derive & store message keys for [Nr, until) on the current receiving chain.
    private static func skipMessageKeys(_ state: RatchetState, until: UInt32) throws {
        guard state.CKr != nil else { return }
        if Int(until) - Int(state.Nr) > maxSkip { throw CryptoError.tooManySkippedMessages }
        guard let dhr = state.DHr else { return }
        while state.Nr < until {
            let (ck, mk) = kdfCK(state.CKr!)
            state.CKr = ck
            state.MKSKIPPED[skKey(dhr, state.Nr)] = mk
            state.Nr += 1
        }
    }

    /// DH ratchet step in response to a new ratchet public key in `header`.
    private static func dhRatchet(_ state: RatchetState, _ header: Header) throws {
        state.PN = state.Ns
        state.Ns = 0
        state.Nr = 0
        state.DHr = Data(header.dh)
        let step1 = kdfRK(state.RK, try dh(state.DHs, state.DHr!))
        state.RK = step1.rk
        state.CKr = step1.ck
        state.DHs = generateX25519()
        let step2 = kdfRK(state.RK, try dh(state.DHs, state.DHr!))
        state.RK = step2.rk
        state.CKs = step2.ck
    }

    static func ratchetDecrypt(_ state: RatchetState,
                               header: Header,
                               ciphertext: Data,
                               associatedData: Data) throws -> Data {
        let hdr = Header(dh: Data(header.dh), pn: header.pn, n: header.n)

        if let fromSkipped = try trySkipped(state, hdr, ciphertext, associatedData) {
            return fromSkipped
        }

        if state.DHr == nil || !ctEqual(hdr.dh, state.DHr!) {
            try skipMessageKeys(state, until: hdr.pn) // finish the previous chain
            try dhRatchet(state, hdr)
        }
        try skipMessageKeys(state, until: hdr.n)       // catch up on current chain
        guard let ckr = state.CKr else { throw CryptoError.badState }
        let (ck, mk) = kdfCK(ckr)
        state.CKr = ck
        state.Nr += 1
        return try openWithMk(mk, hdr, ciphertext, associatedData)
    }

    // ==========================================================================
    // SECTION 3 — Chunked file encryption (per-file key).
    //
    //   nonce_i = fileNonce(8) || uint32be(i)          (12-byte GCM nonce)
    //   chunk i = AES-256-GCM(fileKey, nonce_i, plaintext) as ct || tag
    //   manifest = AES-256-GCM(fileKey, nonce_{0xFFFFFFFF}, JSON(info)) as ct||tag
    // ==========================================================================

    static let manifestCounter: UInt32 = 0xffffffff

    static func chunkNonce(_ fileNonce: Data, _ counter: UInt32) -> Data {
        var d = Data(fileNonce)
        d.append(u32be(counter))
        return d
    }

    /// Build the compact manifest JSON reproducing Node `JSON.stringify` key
    /// ordering + formatting: {"filename":..,"mime":..,"size":N,"chunkCount":N,"chunkSize":N}.
    static func manifestJSON(filename: String, mime: String, size: Int, chunkCount: Int, chunkSize: Int) -> Data {
        let f = jsonEscape(filename)
        let m = jsonEscape(mime)
        let s = "{\"filename\":\"\(f)\",\"mime\":\"\(m)\",\"size\":\(size),\"chunkCount\":\(chunkCount),\"chunkSize\":\(chunkSize)}"
        return Data(s.utf8)
    }

    /// Minimal JSON string escaping matching Node `JSON.stringify` for the common
    /// cases (quote, backslash, control chars). Sufficient for filenames/mime.
    private static func jsonEscape(_ s: String) -> String {
        var out = ""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        return out
    }

    /// - Parameters:
    ///   - reuseFileKey/reuseFileNonce: supply BOTH to re-derive a previously
    ///     produced ciphertext byte-for-byte instead of drawing fresh key material.
    ///     MECHANISM: every chunk nonce is `fileNonce || u32be(i)` (`chunkNonce`)
    ///     and AES-GCM is deterministic, so the same (key, nonce, plaintext) yields
    ///     the exact same `ct||tag`. That is what lets an interrupted upload resume
    ///     into the SAME server-side blob after an app relaunch (see
    ///     `V2SendResumeStore`) — the server's already-stored chunks stay valid.
    ///     ⚠️ Only ever pass key material back with the IDENTICAL plaintext:
    ///     reusing a GCM (key, nonce) pair across different plaintexts is fatal.
    ///     `V2SendResumeStore` enforces that with a SHA-256 of the plaintext.
    static func encryptFile(bytes: Data,
                            chunkSize: Int = 2 * 1024 * 1024,
                            meta: FileMeta = FileMeta(),
                            reuseFileKey: Data? = nil,
                            reuseFileNonce: Data? = nil) throws -> EncryptedFile {
        // Both or neither: a reused key paired with a fresh nonce (or vice versa)
        // is a caller bug, and the half-reused case would silently produce a blob
        // the server's stored chunks no longer match.
        guard (reuseFileKey == nil) == (reuseFileNonce == nil) else { throw CryptoError.badState }
        let fileKey = reuseFileKey ?? randomBytes(32)
        let fileNonce = reuseFileNonce ?? randomBytes(8)

        var chunks: [Data] = []
        var off = 0
        var i: UInt32 = 0
        while off < bytes.count {
            let end = min(off + chunkSize, bytes.count)
            let slice = Data(bytes[bytes.startIndex.advanced(by: off)..<bytes.startIndex.advanced(by: end)])
            chunks.append(try aesGcmSeal(key: fileKey, nonce: chunkNonce(fileNonce, i), plaintext: slice, aad: Data()))
            off += chunkSize
            i += 1
        }

        let infoJSON = manifestJSON(
            filename: meta.filename,
            mime: meta.mime,
            size: bytes.count,
            chunkCount: chunks.count,
            chunkSize: chunkSize
        )
        let manifest = try aesGcmSeal(
            key: fileKey,
            nonce: chunkNonce(fileNonce, manifestCounter),
            plaintext: infoJSON,
            aad: Data()
        )
        return EncryptedFile(fileKey: fileKey, fileNonce: fileNonce, chunks: chunks, manifest: manifest, infoJSON: infoJSON)
    }

    static func decryptFile(fileKey: Data,
                            fileNonce: Data,
                            chunks: [Data],
                            manifest: Data) throws -> (bytes: Data, info: [String: Any]) {
        let infoData = try aesGcmOpen(
            key: fileKey,
            nonce: chunkNonce(fileNonce, manifestCounter),
            ctAndTag: manifest,
            aad: Data()
        )
        guard let info = try JSONSerialization.jsonObject(with: infoData) as? [String: Any],
              let chunkCount = (info["chunkCount"] as? NSNumber)?.intValue,
              let size = (info["size"] as? NSNumber)?.intValue else {
            throw CryptoError.badState
        }
        guard chunkCount == chunks.count else { throw CryptoError.chunkCountMismatch }
        var out = Data()
        for (idx, c) in chunks.enumerated() {
            out.append(try aesGcmOpen(
                key: fileKey,
                nonce: chunkNonce(fileNonce, UInt32(idx)),
                ctAndTag: c,
                aad: Data()
            ))
        }
        guard out.count == size else { throw CryptoError.sizeMismatch }
        return (out, info)
    }

    // ==========================================================================
    // MARK: - Streaming file crypto (same wire format, bounded memory)
    //
    // `encryptFile`/`decryptFile` above hold the WHOLE file as `Data` *and* build
    // the whole `[Data]` chunk array, so peak RSS is ~2x the file size (plaintext
    // + ciphertext) on both ends. That is fine for a photo and fatal for a large
    // attachment: a 200 MB file spikes ~400 MB and iOS jetsams the app. These
    // variants stream through a fixed ~chunkSize window, so peak memory is flat
    // (~2 MiB) no matter how big the file is — that, not the size constants, is
    // what actually gates large attachments.
    //
    // The bytes produced are IDENTICAL to the in-memory path: same per-chunk
    // nonce (fileNonce||u32be(i)), same manifest nonce (0xFFFFFFFF), same
    // manifest JSON. OSHICryptoV2Tests cross-decrypts each path with the other to
    // keep it that way, so a peer on any build can read either. Do not "optimise"
    // the layout here without breaking that compatibility.
    // ==========================================================================

    /// Result of `encryptFileStreaming`. Chunks live on disk, not in RAM.
    struct StreamingEncryptedFile {
        let fileKey: Data            // 32 random bytes
        let fileNonce: Data          // 8 random bytes (base for every chunk nonce)
        let chunkURLs: [URL]         // each file = ct || tag, in chunk order
        let manifest: Data           // encrypted manifest blob (ct || tag)
        let infoJSON: Data           // plaintext manifest JSON (caller convenience)
        let size: Int                // plaintext byte count
        var chunkCount: Int { chunkURLs.count }
    }

    /// Encrypt `inputURL` into per-chunk ciphertext files under `outputDir`.
    /// Peak memory ≈ chunkSize, independent of file size.
    /// `reuseFileKey`/`reuseFileNonce`: see `encryptFile` — same determinism
    /// contract, used to resume an interrupted send into the same blob.
    static func encryptFileStreaming(inputURL: URL,
                                     outputDir: URL,
                                     chunkSize: Int = 2 * 1024 * 1024,
                                     meta: FileMeta = FileMeta(),
                                     reuseFileKey: Data? = nil,
                                     reuseFileNonce: Data? = nil) throws -> StreamingEncryptedFile {
        guard chunkSize > 0 else { throw CryptoError.badState }
        guard (reuseFileKey == nil) == (reuseFileNonce == nil) else { throw CryptoError.badState }
        let fileKey = reuseFileKey ?? randomBytes(32)
        let fileNonce = reuseFileNonce ?? randomBytes(8)

        try FileManager.default.createDirectory(at: outputDir, withIntermediateDirectories: true)
        let input = try FileHandle(forReadingFrom: inputURL)
        defer { try? input.close() }

        var chunkURLs: [URL] = []
        var total = 0
        var counter: UInt32 = 0
        while true {
            // autoreleasepool so each slice's buffers are reclaimed per iteration
            // rather than piling up until the loop ends — that pile-up is exactly
            // what the in-memory path does wrong.
            let produced: Int = try autoreleasepool {
                guard let slice = try input.read(upToCount: chunkSize), !slice.isEmpty else { return 0 }
                let sealed = try aesGcmSeal(key: fileKey,
                                            nonce: chunkNonce(fileNonce, counter),
                                            plaintext: slice,
                                            aad: Data())
                let url = outputDir.appendingPathComponent("\(counter).chunk")
                try sealed.write(to: url, options: .atomic)
                chunkURLs.append(url)
                return slice.count
            }
            if produced == 0 { break }   // EOF (an empty file yields 0 chunks, matching encryptFile)
            total += produced
            counter += 1
        }

        let infoJSON = manifestJSON(
            filename: meta.filename,
            mime: meta.mime,
            size: total,
            chunkCount: chunkURLs.count,
            chunkSize: chunkSize
        )
        let manifest = try aesGcmSeal(
            key: fileKey,
            nonce: chunkNonce(fileNonce, manifestCounter),
            plaintext: infoJSON,
            aad: Data()
        )
        return StreamingEncryptedFile(fileKey: fileKey,
                                      fileNonce: fileNonce,
                                      chunkURLs: chunkURLs,
                                      manifest: manifest,
                                      infoJSON: infoJSON,
                                      size: total)
    }

    /// Decrypt per-chunk ciphertext files straight to `outputURL` on disk.
    /// Peak memory ≈ chunkSize. Returns the manifest info dictionary.
    /// On any failure the partial output is deleted — never leave a half-written
    /// file that looks like a real attachment.
    @discardableResult
    static func decryptFileStreaming(fileKey: Data,
                                     fileNonce: Data,
                                     chunkURLs: [URL],
                                     manifest: Data,
                                     outputURL: URL) throws -> [String: Any] {
        let infoData = try aesGcmOpen(
            key: fileKey,
            nonce: chunkNonce(fileNonce, manifestCounter),
            ctAndTag: manifest,
            aad: Data()
        )
        guard let info = try JSONSerialization.jsonObject(with: infoData) as? [String: Any],
              let chunkCount = (info["chunkCount"] as? NSNumber)?.intValue,
              let size = (info["size"] as? NSNumber)?.intValue else {
            throw CryptoError.badState
        }
        guard chunkCount == chunkURLs.count else { throw CryptoError.chunkCountMismatch }

        let fm = FileManager.default
        try? fm.removeItem(at: outputURL)
        guard fm.createFile(atPath: outputURL.path, contents: nil) else { throw CryptoError.badState }
        let out = try FileHandle(forWritingTo: outputURL)

        var written = 0
        do {
            for (idx, url) in chunkURLs.enumerated() {
                try autoreleasepool {
                    let ct = try Data(contentsOf: url, options: .mappedIfSafe)
                    let pt = try aesGcmOpen(key: fileKey,
                                            nonce: chunkNonce(fileNonce, UInt32(idx)),
                                            ctAndTag: ct,
                                            aad: Data())
                    try out.write(contentsOf: pt)
                    written += pt.count
                }
            }
            guard written == size else { throw CryptoError.sizeMismatch }
        } catch {
            try? out.close()
            try? fm.removeItem(at: outputURL)
            throw error
        }
        try? out.close()
        return info
    }
}
