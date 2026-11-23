//
//  ObliviousHTTP.swift
//  OSHI
//
//  __OHTTP_PRIVACY_RELAY_2026_11_09__ Oblivious HTTP client (RFC 9458) for the
//  OSHI v2 API. When the user turns on "Privacy relay (beta)", every `/v2/*`
//  control-plane request that `V2Client` makes goes
//
//      Client ──POST message/ohttp-req──▶ independent OHTTP Relay
//             ──▶ OSHI Gateway (https://oshi-messenger.com/ohttp/gateway) ──▶ v2
//
//  The relay sees the user's IP but only an HPKE-sealed blob; OSHI's gateway
//  sees the (already end-to-end encrypted) request but only the relay's IP.
//
//  Contract (shared with the gateway and the Android client — keep identical):
//    * HPKE suite: DHKEM(X25519, HKDF-SHA256) 0x0020 / HKDF-SHA256 0x0001 /
//      AES-128-GCM 0x0001 (CryptoKit `OHTTP.ciphersuite`).
//    * Request encapsulation RFC 9458 §4.3, response §4.4.
//    * Inner message: Binary HTTP, known-length (RFC 9292), scheme "https",
//      authority "oshi-messenger.com", path = the exact percent-encoded path
//      (+ query) the request would have used directly. The `x-oshi-*` HTTP
//      signature headers were computed by `V2Client.signRequest` over that same
//      path, so they verify unchanged behind the gateway.
//    * Key config: RFC 9458 §3 / RFC 9540 `application/ohttp-keys`. The config
//      is PINNED in the app (`OHTTPSettings.pinnedKeyConfigHex`) and changes
//      only with an app update: fetching it at runtime would either reveal the
//      IP to OSHI (direct GET) or let a relay substitute keys — both defeat
//      the point. A gateway key rotation surfaces as `OHTTPError.keyConfigMismatch`.
//
//  Scope: every `/v2/*` call (messages send/poll/ack, prekeys, devices, config)
//  AND, since __OHTTP_MEDIA_2026_11_10__, media blobs (`/v2/blobs*`: reserve,
//  chunk PUT, status, commit, chunk GET, DELETE). Background URLSessions cannot
//  re-encapsulate, so with the relay on `V2Client` moves blob transfers to the
//  foreground session (`V2Transport.blobsMustStayForeground`) — every one of
//  them goes through `V2Transport.data`. Calls, the devsync WebSocket and legacy
//  `/api/*` routes stay direct and still reveal the IP to OSHI; the settings
//  footer says so. The gateway accepts 2 MiB + 64 KiB bodies (a full chunk is
//  2 MiB + 16 B plus BHTTP/HPKE framing).
//
//  NO SILENT FALLBACK: when the relay is enabled and the OHTTP exchange fails,
//  the request FAILS (transient errors are retried by `V2Client.withV2Retry`
//  and the outbox). Quietly going direct would hand OSHI the IP the user
//  asked to hide.
//

import Foundation
import CryptoKit
import Security

// MARK: - Errors

nonisolated enum OHTTPError: Error, Equatable {
    /// OHTTP needs CryptoKit HPKE (iOS 17 / macOS 14). Never falls back.
    case unavailable
    /// The relay is enabled in settings but no relay URL / key config is built in.
    case notConfigured
    case invalidKeyConfig(String)
    case unsupportedKeyConfig
    case bhttpMalformed(String)
    case responseMalformed
    case decryptionFailed
    /// The relay (or the gateway behind it) answered with a non-200 status.
    case relayStatus(Int)
    /// The gateway did not recognise our pinned key (RFC 9458 §5.3,
    /// problem type `ohttp-key`). The app needs an update with the new config.
    case keyConfigMismatch
    case pathNotRoutable(String)

    /// Worth a backoff-retry (the relay or gateway was momentarily unhappy).
    var isTransient: Bool {
        if case .relayStatus(let s) = self { return s == 429 || (500...599).contains(s) }
        return false
    }
}

// MARK: - QUIC variable-length integers (RFC 9000 §16)

nonisolated enum QUICVarint {
    static let max: UInt64 = (1 << 62) - 1

    static func encode(_ value: UInt64, into out: inout Data) {
        precondition(value <= max, "varint out of range")
        switch value {
        case 0..<(1 << 6):
            out.append(UInt8(value))
        case (1 << 6)..<(1 << 14):
            out.append(UInt8(0x40 | (value >> 8)))
            out.append(UInt8(value & 0xff))
        case (1 << 14)..<(1 << 30):
            out.append(UInt8(0x80 | (value >> 24)))
            for shift in stride(from: 16, through: 0, by: -8) { out.append(UInt8((value >> UInt64(shift)) & 0xff)) }
        default:
            out.append(UInt8(0xc0 | (value >> 56)))
            for shift in stride(from: 48, through: 0, by: -8) { out.append(UInt8((value >> UInt64(shift)) & 0xff)) }
        }
    }

    static func encode(_ value: UInt64) -> Data {
        var d = Data(); encode(value, into: &d); return d
    }
}

/// Bounds-checked cursor over a byte buffer.
nonisolated struct OHTTPByteReader {
    private let bytes: [UInt8]
    private(set) var offset = 0
    init(_ data: Data) { bytes = [UInt8](data) }

    var isAtEnd: Bool { offset >= bytes.count }
    var remaining: Int { bytes.count - offset }

    mutating func readByte() throws -> UInt8 {
        guard offset < bytes.count else { throw OHTTPError.bhttpMalformed("truncated") }
        defer { offset += 1 }
        return bytes[offset]
    }

    mutating func readBytes(_ n: Int) throws -> Data {
        guard n >= 0, n <= remaining else { throw OHTTPError.bhttpMalformed("truncated") }
        defer { offset += n }
        return Data(bytes[offset..<(offset + n)])
    }

    mutating func readUInt16() throws -> UInt16 {
        let hi = try readByte(), lo = try readByte()
        return (UInt16(hi) << 8) | UInt16(lo)
    }

    mutating func readVarint() throws -> UInt64 {
        let first = try readByte()
        let length = 1 << Int(first >> 6)
        var value = UInt64(first & 0x3f)
        for _ in 1..<length { value = (value << 8) | UInt64(try readByte()) }
        return value
    }

    mutating func readLengthPrefixed() throws -> Data {
        let n = try readVarint()
        guard n <= UInt64(remaining) else { throw OHTTPError.bhttpMalformed("length exceeds buffer") }
        return try readBytes(Int(n))
    }

    /// True when only zero bytes (RFC 9292 §3.8 padding) remain.
    var restIsPadding: Bool { bytes[offset...].allSatisfy { $0 == 0 } }
}

// MARK: - Binary HTTP, known-length (RFC 9292)

nonisolated enum BinaryHTTP {
    struct Request: Equatable {
        var method: String
        var scheme: String
        var authority: String
        var path: String
        var headers: [(name: String, value: String)]
        var content: Data

        static func == (a: Request, b: Request) -> Bool {
            a.method == b.method && a.scheme == b.scheme && a.authority == b.authority && a.path == b.path
                && a.content == b.content
                && a.headers.map { "\($0.name):\($0.value)" } == b.headers.map { "\($0.name):\($0.value)" }
        }
    }

    struct Response: Equatable {
        var status: Int
        var headers: [(name: String, value: String)]
        var content: Data

        static func == (a: Response, b: Response) -> Bool {
            a.status == b.status && a.content == b.content
                && a.headers.map { "\($0.name):\($0.value)" } == b.headers.map { "\($0.name):\($0.value)" }
        }

        func header(_ name: String) -> String? {
            headers.first { $0.name.lowercased() == name.lowercased() }?.value
        }
    }

    static let knownLengthRequest: UInt64 = 0
    static let knownLengthResponse: UInt64 = 1

    private static func appendLP(_ bytes: Data, to out: inout Data) {
        QUICVarint.encode(UInt64(bytes.count), into: &out)
        out.append(bytes)
    }

    private static func encodeFields(_ fields: [(name: String, value: String)]) -> Data {
        var section = Data()
        for f in fields {
            // RFC 9292 §3.6: field names are lowercase on the wire.
            appendLP(Data(f.name.lowercased().utf8), to: &section)
            appendLP(Data(f.value.utf8), to: &section)
        }
        var out = Data()
        appendLP(section, to: &out)
        return out
    }

    private static func decodeFields(_ r: inout OHTTPByteReader) throws -> [(name: String, value: String)] {
        if r.isAtEnd { return [] }   // §3.8 truncation
        var section = OHTTPByteReader(try r.readLengthPrefixed())
        var out: [(name: String, value: String)] = []
        while !section.isAtEnd {
            let name = try section.readLengthPrefixed()
            let value = try section.readLengthPrefixed()
            guard let n = String(data: name, encoding: .utf8), let v = String(data: value, encoding: .utf8) else {
                throw OHTTPError.bhttpMalformed("non-UTF-8 field")
            }
            out.append((n, v))
        }
        return out
    }

    /// - Parameter truncate: omit trailing empty sections (RFC 9292 §3.8). Off by
    ///   default — the full form is what the gateway is tested against; on for
    ///   reproducing the RFC 9458 Appendix A vector byte-for-byte.
    static func encode(_ req: Request, truncate: Bool = false) -> Data {
        var out = Data()
        QUICVarint.encode(knownLengthRequest, into: &out)
        appendLP(Data(req.method.utf8), to: &out)
        appendLP(Data(req.scheme.utf8), to: &out)
        appendLP(Data(req.authority.utf8), to: &out)
        appendLP(Data(req.path.utf8), to: &out)
        if truncate && req.headers.isEmpty && req.content.isEmpty { return out }
        out.append(encodeFields(req.headers))
        if truncate && req.content.isEmpty { return out }
        appendLP(req.content, to: &out)
        if truncate { return out }
        out.append(encodeFields([]))   // empty trailers
        return out
    }

    static func encode(_ resp: Response, truncate: Bool = false) -> Data {
        var out = Data()
        QUICVarint.encode(knownLengthResponse, into: &out)
        QUICVarint.encode(UInt64(resp.status), into: &out)
        if truncate && resp.headers.isEmpty && resp.content.isEmpty { return out }
        out.append(encodeFields(resp.headers))
        if truncate && resp.content.isEmpty { return out }
        appendLP(resp.content, to: &out)
        if truncate { return out }
        out.append(encodeFields([]))
        return out
    }

    static func decodeRequest(_ data: Data) throws -> Request {
        var r = OHTTPByteReader(data)
        guard try r.readVarint() == knownLengthRequest else { throw OHTTPError.bhttpMalformed("not a known-length request") }
        func str(_ d: Data) throws -> String {
            guard let s = String(data: d, encoding: .utf8) else { throw OHTTPError.bhttpMalformed("non-UTF-8 control data") }
            return s
        }
        let method = try str(r.readLengthPrefixed())
        let scheme = try str(r.readLengthPrefixed())
        let authority = try str(r.readLengthPrefixed())
        let path = try str(r.readLengthPrefixed())
        let headers = try decodeFields(&r)
        let content = r.isAtEnd ? Data() : try r.readLengthPrefixed()
        _ = try decodeFields(&r)   // trailers (ignored)
        guard r.restIsPadding else { throw OHTTPError.bhttpMalformed("trailing garbage") }
        return Request(method: method, scheme: scheme, authority: authority, path: path, headers: headers, content: content)
    }

    /// Decodes a known-length response, skipping any informational (1xx)
    /// responses that precede the final one (RFC 9292 §3.5.1).
    static func decodeResponse(_ data: Data) throws -> Response {
        var r = OHTTPByteReader(data)
        guard try r.readVarint() == knownLengthResponse else { throw OHTTPError.bhttpMalformed("not a known-length response") }
        var status = try r.readVarint()
        while (100...199).contains(status) {
            _ = try decodeFields(&r)            // informational header section
            status = try r.readVarint()
        }
        guard (200...599).contains(status) else { throw OHTTPError.bhttpMalformed("bad status \(status)") }
        let headers = try decodeFields(&r)
        let content = r.isAtEnd ? Data() : try r.readLengthPrefixed()
        _ = try decodeFields(&r)
        guard r.restIsPadding else { throw OHTTPError.bhttpMalformed("trailing garbage") }
        return Response(status: Int(status), headers: headers, content: content)
    }
}

// MARK: - Key configuration (RFC 9458 §3, RFC 9540)

nonisolated struct OHTTPKeyConfig: Equatable {
    static let kemX25519: UInt16 = 0x0020
    static let kdfHKDFSHA256: UInt16 = 0x0001
    static let aeadAES128GCM: UInt16 = 0x0001
    static let x25519PublicKeyLength = 32

    let keyID: UInt8
    let kemID: UInt16
    let publicKey: Data
    let symmetric: [(kdf: UInt16, aead: UInt16)]

    static func == (a: OHTTPKeyConfig, b: OHTTPKeyConfig) -> Bool {
        a.keyID == b.keyID && a.kemID == b.kemID && a.publicKey == b.publicKey
            && a.symmetric.map { [$0.kdf, $0.aead] } == b.symmetric.map { [$0.kdf, $0.aead] }
    }

    /// True when this config offers exactly the suite OSHI speaks.
    var supportsOSHISuite: Bool {
        kemID == Self.kemX25519 && publicKey.count == Self.x25519PublicKeyLength
            && symmetric.contains { $0.kdf == Self.kdfHKDFSHA256 && $0.aead == Self.aeadAES128GCM }
    }

    private static func parseOne(_ r: inout OHTTPByteReader) throws -> OHTTPKeyConfig {
        let keyID = try r.readByte()
        let kem = try r.readUInt16()
        // Only X25519 has a known Npk here; other KEMs make the config unparseable
        // in isolation, which is why RFC 9540 length-prefixes each config.
        guard kem == kemX25519 else { throw OHTTPError.unsupportedKeyConfig }
        let pk = try r.readBytes(x25519PublicKeyLength)
        let symLen = Int(try r.readUInt16())
        guard symLen > 0, symLen % 4 == 0, symLen <= r.remaining else { throw OHTTPError.invalidKeyConfig("symmetric length") }
        var syms: [(kdf: UInt16, aead: UInt16)] = []
        for _ in 0..<(symLen / 4) { syms.append((try r.readUInt16(), try r.readUInt16())) }
        return OHTTPKeyConfig(keyID: keyID, kemID: kem, publicKey: pk, symmetric: syms)
    }

    /// A single, unprefixed key config (RFC 9458 §3.1 — the Appendix A form).
    static func parse(_ data: Data) throws -> OHTTPKeyConfig {
        var r = OHTTPByteReader(data)
        let c = try parseOne(&r)
        guard r.isAtEnd else { throw OHTTPError.invalidKeyConfig("trailing bytes") }
        return c
    }

    /// `application/ohttp-keys` (RFC 9540 §3): a sequence of 2-byte
    /// length-prefixed configs. Configs for KEMs we do not speak are skipped.
    static func parseList(_ data: Data) throws -> [OHTTPKeyConfig] {
        var r = OHTTPByteReader(data)
        var out: [OHTTPKeyConfig] = []
        while !r.isAtEnd {
            let len = Int(try r.readUInt16())
            let body = try r.readBytes(len)
            if let c = try? parse(body) { out.append(c) }
        }
        guard !out.isEmpty else { throw OHTTPError.unsupportedKeyConfig }
        return out
    }

    /// Accepts either form (a pinned blob may be copied from either source).
    static func parseAny(_ data: Data) throws -> [OHTTPKeyConfig] {
        if let single = try? parse(data) { return [single] }
        return try parseList(data)
    }

    /// The first config offering the OSHI suite.
    static func select(from configs: [OHTTPKeyConfig]) -> OHTTPKeyConfig? {
        configs.first { $0.supportsOSHISuite }
    }

    /// RFC 9458 §4.3 header: key_id(1) | kem_id(2) | kdf_id(2) | aead_id(2).
    var requestHeader: Data {
        var h = Data([keyID])
        for v in [kemID, Self.kdfHKDFSHA256, Self.aeadAES128GCM] { h.append(UInt8(v >> 8)); h.append(UInt8(v & 0xff)) }
        return h
    }

    /// HPKE info = "message/bhttp request" ‖ 0x00 ‖ header.
    var requestInfo: Data {
        var info = Data(OHTTP.requestLabel.utf8)
        info.append(0)
        info.append(requestHeader)
        return info
    }
}

// MARK: - Encapsulation (RFC 9458 §4.3 / §4.4)

nonisolated enum OHTTP {
    static let requestLabel = "message/bhttp request"
    static let responseLabel = "message/bhttp response"
    static let requestContentType = "message/ohttp-req"
    static let responseContentType = "message/ohttp-res"
    /// max(Nn = 12, Nk = 16) for AES-128-GCM.
    static let responseNonceLength = 16
    static let aeadKeyLength = 16
    static let aeadNonceLength = 12

    /// DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / AES-128-GCM. CryptoKit has no
    /// predefined constant for this suite (its X25519 preset is ChaChaPoly), so
    /// it is assembled from its parts.
    @available(iOS 17.0, macOS 14.0, *)
    static var ciphersuite: HPKE.Ciphersuite {
        HPKE.Ciphersuite(kem: .Curve25519_HKDF_SHA256, kdf: .HKDF_SHA256, aead: .AES_GCM_128)
    }

    /// What the client keeps to open the matching response.
    struct ResponseContext {
        let enc: Data
        let exportedSecret: Data
    }

    @available(iOS 17.0, macOS 14.0, *)
    static func encapsulateRequest(_ bhttp: Data, config: OHTTPKeyConfig) throws -> (Data, ResponseContext) {
        guard config.supportsOSHISuite else { throw OHTTPError.unsupportedKeyConfig }
        let pk: Curve25519.KeyAgreement.PublicKey
        do { pk = try .init(rawRepresentation: config.publicKey) } catch { throw OHTTPError.invalidKeyConfig("public key") }
        var sender = try HPKE.Sender(recipientKey: pk, ciphersuite: ciphersuite, info: config.requestInfo)
        let ct = try sender.seal(bhttp)
        let enc = sender.encapsulatedKey
        let secret = try sender.exportSecret(context: Data(responseLabel.utf8), outputByteCount: responseNonceLength)
        var out = config.requestHeader
        out.append(enc)
        out.append(ct)
        return (out, ResponseContext(enc: enc, exportedSecret: secret.withUnsafeBytes { Data($0) }))
    }

    /// AEAD key + nonce for the response (§4.4). Pure, so the RFC vector can
    /// check it with a secret exported from `HPKE.Recipient`.
    static func responseKeyAndNonce(secret: Data, enc: Data, responseNonce: Data) -> (key: SymmetricKey, nonce: Data) {
        var salt = enc
        salt.append(responseNonce)
        let prk = HKDF<SHA256>.extract(inputKeyMaterial: SymmetricKey(data: secret), salt: salt)
        let key = HKDF<SHA256>.expand(pseudoRandomKey: prk, info: Data("key".utf8), outputByteCount: aeadKeyLength)
        let nonce = HKDF<SHA256>.expand(pseudoRandomKey: prk, info: Data("nonce".utf8), outputByteCount: aeadNonceLength)
        return (key, nonce.withUnsafeBytes { Data($0) })
    }

    static func decapsulateResponse(_ data: Data, context: ResponseContext) throws -> Data {
        guard data.count >= responseNonceLength + 16 else { throw OHTTPError.responseMalformed }
        let responseNonce = data.prefix(responseNonceLength)
        let sealed = data.dropFirst(responseNonceLength)
        let (key, nonce) = responseKeyAndNonce(secret: context.exportedSecret, enc: context.enc, responseNonce: Data(responseNonce))
        do {
            let box = try AES.GCM.SealedBox(combined: nonce + sealed)
            return try AES.GCM.open(box, using: key)
        } catch {
            throw OHTTPError.decryptionFailed
        }
    }

    /// Gateway side of §4.4 — used by tests to build responses.
    static func encapsulateResponse(_ bhttp: Data, secret: Data, enc: Data, responseNonce: Data) throws -> Data {
        let (key, nonce) = responseKeyAndNonce(secret: secret, enc: enc, responseNonce: responseNonce)
        let box = try AES.GCM.seal(bhttp, using: key, nonce: AES.GCM.Nonce(data: nonce))
        return responseNonce + box.ciphertext + box.tag
    }
}

// MARK: - Settings

nonisolated enum OHTTPSettings {
    /// Persisted user choice ("Privacy relay (beta)"), OFF by default.
    static let enabledKey = "ohttpPrivacyRelayEnabled"
    static let appGroup = "group.Moriceau-lab.Genesis"

    /// OSHI's Oblivious Gateway Resource (RFC 9458 §6.4).
    static let gatewayURL = URL(string: "https://oshi-messenger.com/ohttp/gateway")!
    /// Authority of the inner (target) request.
    static let targetAuthority = "oshi-messenger.com"

    /// 🔴 PLACEHOLDER — the independent Oblivious Relay Resource. Empty = the
    /// feature is not offered (toggle disabled). Must be operated by a party
    /// OTHER than OSHI, and configured to forward to `gatewayURL`.
    static let relayURLString = ""

    /// The gateway's key config, hex, either the single RFC 9458 §3 form or the
    /// RFC 9540 `application/ohttp-keys` list. Shipped with app updates only
    /// (see header comment). Empty = not offered. Pinned 2026-11-09 from the
    /// live gateway: key_id 0x01, X25519 / HKDF-SHA256 / AES-128-GCM — byte-equal
    /// to what `GET /ohttp/config` serves (after its 2-byte length 0x0029).
    static let pinnedKeyConfigHex = "010020c93f9075d86e2e7dca66b2e3309d584486ef53f7d6dca5cbff3d4ccf87cda019000400010001"

    /// __OHTTP_RELAY_ADDRESS_2026_11_10__ The relay the user entered (Settings →
    /// Privacy → Relay address). Same key name as Android/desktop (`ohttp_relay_url`
    /// there). Lets the feature be switched on without an app update, and lets a user
    /// whose relay is blocked point the app at another one — any relay only ever sees
    /// ciphertext sealed to the pinned gateway key.
    static let relayURLKey = "ohttpPrivacyRelayURL"

    /// The address the user entered ("" = none), mirrored to the app group so the
    /// notification/share extensions route the same way.
    static var userRelayURLString: String {
        let shared = UserDefaults(suiteName: appGroup)
        let isExtension = Bundle.main.bundleURL.pathExtension == "appex"
        if isExtension { return (shared ?? .standard).string(forKey: relayURLKey) ?? "" }
        let value = UserDefaults.standard.string(forKey: relayURLKey) ?? ""
        shared?.set(value, forKey: relayURLKey)
        return value
    }

    /// Save a relay address. False (nothing saved) when unacceptable; blank clears it
    /// and, with no built-in relay, turns the switch off.
    @discardableResult
    static func setUserRelayURL(_ input: String) -> Bool {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let value: String
        if trimmed.isEmpty { value = "" } else {
            guard let n = normalizeRelayURL(trimmed) else { return false }
            value = n
        }
        UserDefaults.standard.set(value, forKey: relayURLKey)
        UserDefaults(suiteName: appGroup)?.set(value, forKey: relayURLKey)
        if value.isEmpty && relayURLString.isEmpty {
            UserDefaults.standard.set(false, forKey: enabledKey)
            UserDefaults(suiteName: appGroup)?.set(false, forKey: enabledKey)
        }
        return true
    }

    /// https only (the relay hop must be encrypted too), a real dotted host, and NOT
    /// OSHI's own domain — the party that decrypts cannot also be the one hiding the IP.
    /// Same rule as Android `OhttpSettings.normalizeRelayUrl` / desktop `DesktopOhttp`.
    static func normalizeRelayURL(_ input: String) -> String? {
        let t = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, let c = URLComponents(string: t),
              c.scheme?.lowercased() == "https",
              let host = c.host?.lowercased(), !host.isEmpty else { return nil }
        if host == targetAuthority || host.hasSuffix("." + targetAuthority) { return nil }
        guard host.contains(".") else { return nil }
        return c.path.isEmpty ? t + "/" : t
    }

    static var relayURL: URL? {
        #if DEBUG
        if let s = ProcessInfo.processInfo.environment["OSHI_OHTTP_RELAY_URL"], let u = URL(string: s) { return u }
        #endif
        let user = userRelayURLString
        if !user.isEmpty, let u = URL(string: user) { return u }
        guard !relayURLString.isEmpty else { return nil }
        return URL(string: relayURLString)
    }

    static var pinnedKeyConfig: OHTTPKeyConfig? {
        var hex = pinnedKeyConfigHex
        #if DEBUG
        if let s = ProcessInfo.processInfo.environment["OSHI_OHTTP_KEY_CONFIG_HEX"] { hex = s }
        #endif
        guard let data = Data(ohttpHex: hex), let list = try? OHTTPKeyConfig.parseAny(data) else { return nil }
        return OHTTPKeyConfig.select(from: list)
    }

    /// HPKE present on this OS (the notification extension targets iOS 16).
    static var isSupportedOS: Bool {
        if #available(iOS 17.0, macOS 14.0, *) { return true }
        return false
    }

    /// The feature can be offered: OS support + relay URL + pinned key built in.
    static var isConfigured: Bool { isSupportedOS && relayURL != nil && pinnedKeyConfig != nil }

    /// The user's raw choice. Mirrored to the app group (same pattern as
    /// `CoverTransport.mode`) so the share extension honours it too.
    static var userEnabled: Bool {
        let shared = UserDefaults(suiteName: appGroup)
        let isExtension = Bundle.main.bundleURL.pathExtension == "appex"
        if isExtension { return (shared ?? .standard).bool(forKey: enabledKey) }
        let value = UserDefaults.standard.bool(forKey: enabledKey)
        shared?.set(value, forKey: enabledKey)
        return value
    }

    /// Effective routing decision. Only true when the build can actually do it;
    /// a build without a relay never shows the toggle as on.
    static var isActive: Bool { userEnabled && relayURL != nil && pinnedKeyConfig != nil }

    /// Every `/v2/*` route, media blobs included (__OHTTP_MEDIA_2026_11_10__ —
    /// the `/v2/blobs*` exclusion of phase 1 is gone).
    static func isRoutable(path: String) -> Bool {
        path.hasPrefix("/v2/")
    }
}

// MARK: - Transport

nonisolated enum OHTTPTransport {
    /// Headers never copied into the inner request (hop-by-hop or set by the
    /// BHTTP control data itself).
    private static let droppedHeaders: Set<String> = ["host", "connection", "content-length", "transfer-encoding",
                                                       "keep-alive", "proxy-connection", "upgrade", "te"]

    /// Build the inner BHTTP request for `request` exactly as it would have hit
    /// `oshi-messenger.com` directly: same method, same percent-encoded path +
    /// query (the one the `x-oshi-signature` was computed over), same headers,
    /// same body.
    static func innerRequest(for request: URLRequest) throws -> BinaryHTTP.Request {
        guard let url = request.url, let comps = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            throw OHTTPError.pathNotRoutable("<no url>")
        }
        var path = comps.percentEncodedPath
        if path.isEmpty { path = "/" }
        if let q = comps.percentEncodedQuery { path += "?" + q }
        var headers: [(name: String, value: String)] = []
        for (k, v) in (request.allHTTPHeaderFields ?? [:]).sorted(by: { $0.key.lowercased() < $1.key.lowercased() })
        where !droppedHeaders.contains(k.lowercased()) {
            headers.append((k.lowercased(), v))
        }
        return BinaryHTTP.Request(method: request.httpMethod ?? "GET", scheme: "https",
                                  authority: OHTTPSettings.targetAuthority, path: path,
                                  headers: headers, content: request.httpBody ?? Data())
    }

    /// Send `request` through the relay. Throws on ANY failure — never goes direct.
    static func data(for request: URLRequest,
                     session: URLSession,
                     relayURL: URL? = OHTTPSettings.relayURL,
                     keyConfig: OHTTPKeyConfig? = OHTTPSettings.pinnedKeyConfig,
                     shortConnections: Bool = false) async throws -> (Data, URLResponse) {
        guard #available(iOS 17.0, macOS 14.0, *) else { throw OHTTPError.unavailable }
        guard let relayURL, let keyConfig else { throw OHTTPError.notConfigured }
        let inner = try innerRequest(for: request)
        guard OHTTPSettings.isRoutable(path: inner.path) else { throw OHTTPError.pathNotRoutable(inner.path) }

        let (encapsulated, context) = try OHTTP.encapsulateRequest(BinaryHTTP.encode(inner), config: keyConfig)
        var outer = URLRequest(url: relayURL)
        outer.httpMethod = "POST"
        outer.timeoutInterval = request.timeoutInterval
        outer.cachePolicy = .reloadIgnoringLocalCacheData
        outer.httpShouldHandleCookies = false
        outer.setValue(OHTTP.requestContentType, forHTTPHeaderField: "Content-Type")
        outer.setValue(OHTTP.responseContentType, forHTTPHeaderField: "Accept")
        outer.httpBody = encapsulated

        // __COVER_DNS_FALLBACK_2026_11_19__ the relay hop resolves through CoverDns too
        // (Android: the relay call goes through the same `noSilentReplay()` client).
        // `shortConnections`: the blocked-host fallback relay is Contabo-hosted like the main
        // server, so on a capped network it gets the same one-connection-per-exchange policy.
        let (raw, response) = try await CoverNet.data(for: outer, session: session, cover: shortConnections)
        guard let http = response as? HTTPURLResponse else { throw OHTTPError.responseMalformed }
        let type = (http.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased()
        guard http.statusCode == 200, type.hasPrefix(OHTTP.responseContentType) else {
            if type.hasPrefix("application/problem+json"),
               let body = String(data: raw, encoding: .utf8), body.contains("ohttp-key") {
                throw OHTTPError.keyConfigMismatch
            }
            throw OHTTPError.relayStatus(http.statusCode == 200 ? 502 : http.statusCode)
        }
        let plaintext = try OHTTP.decapsulateResponse(raw, context: context)
        let decoded = try BinaryHTTP.decodeResponse(plaintext)
        var fields: [String: String] = [:]
        for h in decoded.headers { fields[h.name] = h.value }
        guard let url = request.url,
              let innerResponse = HTTPURLResponse(url: url, statusCode: decoded.status,
                                                  httpVersion: "HTTP/1.1", headerFields: fields) else {
            throw OHTTPError.responseMalformed
        }
        return (decoded.content, innerResponse)
    }
}

// MARK: - V2 choke point

/// The single transport every `V2Client` request goes through. Decides,
/// per request, between Oblivious HTTP (privacy relay on + routable path) and
/// the existing direct / cover path. With the relay on, an OHTTP failure is
/// NEVER retried directly.
nonisolated enum V2Transport {
    static func data(for request: URLRequest, session: URLSession) async throws -> (Data, URLResponse) {
        if OHTTPSettings.isActive,
           let url = request.url,
           OHTTPSettings.isRoutable(path: URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedPath ?? url.path) {
            return try await OHTTPTransport.data(for: request, session: session)
        }
        // __RELAY_FALLBACK_2026_11_19__ privacy relay off: direct/cover, or OSHI's fallback
        // relay when oshi-messenger.com is shown blocked (CoverNetwork.swift).
        return try await RelayFallback.data(for: request, session: session) {
            try await CoverTransport.data(for: request, session: session)
        }
    }

    /// __OHTTP_MEDIA_2026_11_10__ Blob chunk transfers must use the foreground
    /// session (whose every request goes through `data(for:)` above) whenever a
    /// wrapping transport is on: a background URLSession task talks straight to
    /// the API host and would bypass the relay — i.e. silently go direct.
    static var blobsMustStayForeground: Bool {
        // __RELAY_FALLBACK_2026_11_19__ / __BLOB_RANGE_2026_11_19__ also while the blocked-host
        // fallback is in force (a background task would go straight to the blocked host) and
        // on a capped network (only the foreground path reads chunks in ranged parts).
        CoverTransport.mode != nil || OHTTPSettings.isActive || RelayFallback.active() || CoverConnectionPolicy.freshConnections()
    }
}

extension Data {
    /// Lenient hex decoder (whitespace ignored). Empty / odd / non-hex → nil.
    nonisolated init?(ohttpHex hex: String) {
        let clean = hex.filter { !$0.isWhitespace }
        guard !clean.isEmpty, clean.count % 2 == 0 else { return nil }
        var out = Data(capacity: clean.count / 2)
        var it = clean.makeIterator()
        while let a = it.next(), let b = it.next() {
            guard let byte = UInt8(String([a, b]), radix: 16) else { return nil }
            out.append(byte)
        }
        self = out
    }
}
