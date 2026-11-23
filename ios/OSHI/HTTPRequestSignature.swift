//
//  HTTPRequestSignature.swift
//  OSHI
//
//  The canonical string every signed OSHI HTTP request is built from.
//
//  This file is DELIBERATELY tiny and dependency-free (Foundation + CryptoKit, no app
//  types) for one reason: it is the only part of request authentication that can be
//  asserted byte-for-byte without a keychain, a clock or a network, and a scheme whose
//  exact bytes are never asserted is a scheme that drifts. `IdentityManager.signHTTPRequest`
//  — the signer behind `WebSessionManager` (the `/api/web/*` linked-device routes) and
//  `VoiceCallManager` — builds its message here, and `HTTPRequestSignatureTests` pins the
//  result against digests computed outside this codebase.
//
//  The server half is `ServerVPS/v2/oshi_auth.js:244-247`:
//
//      const path     = (req.originalUrl || req.url).split('?')[0];
//      const rawBody  = req.rawBody || …;
//      const bodyHash = sha256(rawBody).hex;
//      const message  = `${req.method}\n${path}\n${bodyHash}\n${timestamp}`;
//
//  Four rules, all of which produce a well-formed signature and a 401 when broken:
//
//   1. PATH EXCLUDES THE QUERY STRING. The server splits on '?' before verifying, so
//      `GET /api/web/sessions?publicKey=…` signs `/api/web/sessions`. The caller holds the
//      full URL, which is exactly why this is the easy mistake — pass the path explicitly.
//   2. A GET HASHES ZERO BYTES. sha256 of nothing is e3b0c442…52b855. It is NOT the hash
//      of `{}` (44136fa3…, what a naive body-parser default would yield) and NOT the hash
//      of the two-byte string `""` (12ae32cb…, which the verify-first blob routes use).
//   3. THE HASH IS LOWERCASE HEX. Upper case verifies against nothing.
//   4. TIMESTAMP IS LAST, in epoch MILLISECONDS as a decimal string; the server allows 30s
//      of skew. Transposing it with the body hash leaves a plausible-looking string whose
//      401 reads like clock skew rather than a field-order bug.
//

import Foundation
import CryptoKit

/// Builder for the canonical string that `x-oshi-signature` is computed over.
///
/// Pure and stateless: no keys, no I/O. Signing (which needs the Ed25519 private key) lives
/// in `IdentityManager.signHTTPRequest`; this type only decides WHAT gets signed.
enum HTTPRequestSignature {

    /// Lowercase-hex SHA-256 of `data`. `nil` and empty `Data` both hash ZERO bytes.
    ///
    /// Rule 2 and rule 3 above live in this one line — the `nil` case is not a convenience,
    /// it is the GET convention: a request with no body signs over the digest of nothing.
    static func bodyHashHex(_ data: Data?) -> String {
        SHA256.hash(data: data ?? Data()).map { String(format: "%02x", $0) }.joined()
    }

    /// `METHOD\nPATH\nSHA256HEX(body)\nTIMESTAMP` — the exact bytes fed to Ed25519.
    ///
    /// - Parameters:
    ///   - method: upper-case HTTP verb, as sent.
    ///   - path: percent-encoded path, **query string excluded** (rule 1).
    ///   - bodyData: the EXACT bytes in the request body, or `nil` for none. Never
    ///     re-serialize between hashing and sending: a dictionary round-tripped through
    ///     `JSONSerialization` twice can reorder its keys, and the resulting 401 looks
    ///     like a clock problem.
    ///   - timestamp: epoch milliseconds, decimal string — take it from
    ///     ``currentTimestampMillis()`` so the same value is signed and sent.
    static func canonicalMessage(method: String,
                                 path: String,
                                 bodyData: Data?,
                                 timestamp: String) -> String {
        "\(method)\n\(path)\n\(bodyHashHex(bodyData))\n\(timestamp)"
    }

    /// Milliseconds since epoch as a decimal string — the `x-oshi-timestamp` format.
    ///
    /// Seconds here would be inside the server's 30s window for the first 30 seconds of
    /// 1970 and rejected forever after, so the unit is load-bearing.
    static func currentTimestampMillis() -> String {
        String(Int(Date().timeIntervalSince1970 * 1000))
    }
}

// MARK: - Call / push request signing (docs/CALL_PUSH_AUTH_CONTRACT.md v1.0)
//
// __CALL_PUSH_AUTH_IOS_2026_09_23__ Every request iOS makes to call_server (`/voip/*`,
// `/api/call/*`) and push_service (`/push/*`) — HTTP routes AND the call-relay WebSocket
// upgrade — goes through ``sign(_:with:timestamp:)``. The key MUST be the Ed25519 key the
// server has TOFU-bound to our X25519 identity, i.e. the Keychain item `signingKey` that
// `V2KeychainCrypto` signs `/v2/*` with (contract §1 "Which key"): a valid signature by any
// other key is a forgery (403) even in `dual`.
//
// Before this helper, three signers (VoIPPushManager, VoiceCallManager, OSHIShare) each
// rebuilt the string from `url.path`. That is byte-identical for today's routes (base64url
// keys, no escapes) but `url.path` percent-DECODES and DROPS the trailing slash — so the WS
// upgrade to `/voip/` would have been signed as `/voip`. The contract accepts both, but
// "sign exactly the request line you send" is the rule that never needs a server exception.
extension HTTPRequestSignature {

    /// The PATH component of the canonical string for `url`: percent-encoding AS SENT,
    /// trailing slash AS SENT, query string EXCLUDED (contract §2).
    static func signingPath(for url: URL) -> String {
        if let comps = URLComponents(url: url, resolvingAgainstBaseURL: true) {
            let p = comps.percentEncodedPath
            return p.isEmpty ? "/" : p
        }
        return url.path.isEmpty ? "/" : url.path
    }

    /// `OSHI-iOS/<marketing version>` — the server's enforce-readiness report counts unsigned
    /// traffic per User-Agent first token (contract §7).
    static var userAgent: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        return "OSHI-iOS/\(v)"
    }

    /// Sign `request` in place with the three `x-oshi-*` headers over
    /// `METHOD\nPATH\nsha256hex(httpBody)\nTIMESTAMP`, and set the User-Agent.
    ///
    /// Signs `request.httpBody` — the exact bytes that go on the wire — so the body must be
    /// final before this is called. A request without a method is treated as `GET` (what
    /// URLSession sends, and what a WebSocket upgrade is). Returns the canonical string that
    /// was signed (for tests / logs), or `nil` if Ed25519 failed.
    @discardableResult
    static func sign(_ request: inout URLRequest,
                     with key: Curve25519.Signing.PrivateKey,
                     timestamp: String = currentTimestampMillis()) -> String? {
        guard let url = request.url else { return nil }
        let method = (request.httpMethod ?? "GET").uppercased()
        let message = canonicalMessage(method: method,
                                       path: signingPath(for: url),
                                       bodyData: request.httpBody,
                                       timestamp: timestamp)
        guard let signature = try? key.signature(for: Data(message.utf8)) else { return nil }
        request.setValue(signature.base64EncodedString(), forHTTPHeaderField: "x-oshi-signature")
        request.setValue(timestamp, forHTTPHeaderField: "x-oshi-timestamp")
        request.setValue(key.publicKey.rawRepresentation.base64EncodedString(),
                         forHTTPHeaderField: "x-oshi-signing-pubkey")
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        return message
    }

    /// Standard base64 → base64url, unpadded — the spelling keys MUST have in call/push URL
    /// paths so no percent-escape is ever needed (contract §2 rule 1).
    static func base64url(_ key: String) -> String {
        key.replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
