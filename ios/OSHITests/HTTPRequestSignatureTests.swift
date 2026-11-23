//
//  HTTPRequestSignatureTests.swift
//  OSHITests
//

import XCTest
import CryptoKit
@testable import OSHI

/// Pins the EXACT BYTES iOS signs for the linked-device routes — `GET /api/web/sessions`,
/// `POST /api/web/sessions/revoke`, `POST /api/web/sessions/revoke-all` — and for every
/// other route that goes through `IdentityManager.signHTTPRequest`.
///
/// Why assert the string and not "signing produced headers": the headers come back populated
/// whatever went into Ed25519. A canonical string with its last two fields transposed, with
/// the query string left on the path, or with upper-case hex yields a perfectly well-formed
/// `x-oshi-signature` that the server rejects as `invalid-signature` — and the rejection
/// reads like clock skew, which is how an afternoon disappears.
///
/// EVERY EXPECTED CONSTANT BELOW WAS COMPUTED OUTSIDE THIS CODEBASE, with
/// `printf … | shasum -a 256`, and not by calling ``HTTPRequestSignature`` a second time:
///
///     printf ''                                             -> e3b0c442…52b855  (zero bytes)
///     printf '{"sessionId":"web-7f3a","publicKey":"Ab+/c="}' -> 1d110b9e…91ab3c
///     printf '""'                                           -> 12ae32cb…82e126
///     printf '{}'                                           -> 44136fa3…aaff8a
///     printf 'GET\n/api/web/sessions\ne3b0…b855\n1757808000000' -> 53de7c75…a9049f (100 B)
///
/// The last is the load-bearing one: it digests the WHOLE 100-byte canonical string the
/// production helper built, so any wrong separator, field order or stray byte changes it.
/// The server builds the same string at `ServerVPS/v2/oshi_auth.js:247`.
final class HTTPRequestSignatureTests: XCTestCase {

    /// A fixed instant, so the string under test is fully determined.
    private let ts = "1757808000000"

    /// `shasum -a 256` of zero bytes — what EVERY GET must hash.
    private let shaZeroBytes = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /// `shasum -a 256` of the revoke body below.
    private let shaRevokeBody = "1d110b9e757bd0060075549171540e94af0de1dd92485518882c5abb6d91ab3c"

    /// `shasum -a 256` of the 2-byte string `""` (the verify-first blob convention).
    private let shaEmptyJSONString = "12ae32cb1ec02d01eda3581b127c1fee3b0dc53572ed6baf239721a03d82e126"

    /// `shasum -a 256` of `{}` — what a naive "empty JSON object" body would hash to.
    private let shaEmptyJSONObject = "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"

    /// `shasum -a 256` of the complete canonical string for the session-list GET.
    private let shaOfWholeGETCanonical =
        "53de7c75c6e456620931df622516b6fe9e436d75bd7e9a94f00efae95da9049f"

    private let revokeBody = #"{"sessionId":"web-7f3a","publicKey":"Ab+/c="}"#

    // MARK: - Rule 2: a GET signs over ZERO bytes

    func testGETSignsOverTheSHA256OfZeroBytes() {
        let fromNil = HTTPRequestSignature.canonicalMessage(
            method: "GET", path: "/api/web/sessions", bodyData: nil, timestamp: ts)
        let fromEmpty = HTTPRequestSignature.canonicalMessage(
            method: "GET", path: "/api/web/sessions", bodyData: Data(), timestamp: ts)

        XCTAssertEqual("GET\n/api/web/sessions\n\(shaZeroBytes)\n\(ts)", fromNil)
        XCTAssertEqual(fromNil, fromEmpty, "nil and empty Data are the same zero-byte body")

        // The two near-misses that each look plausible and each 401.
        XCTAssertNotEqual("GET\n/api/web/sessions\n\(shaEmptyJSONString)\n\(ts)", fromNil,
                          "a GET must not hash the 2-byte string \"\"")
        XCTAssertNotEqual("GET\n/api/web/sessions\n\(shaEmptyJSONObject)\n\(ts)", fromNil,
                          "a GET must not hash {} — an express body-parser default")
    }

    // MARK: - The exact bytes

    func testSessionListGETMatchesAnExternallyComputedDigest() {
        let canonical = HTTPRequestSignature.canonicalMessage(
            method: "GET", path: "/api/web/sessions", bodyData: nil, timestamp: ts)
        let bytes = Data(canonical.utf8)

        // Length and separator count, so two transposed fields of equal length could not
        // slip past the digest check by coincidence.
        XCTAssertEqual(100, bytes.count, "canonical string length")
        XCTAssertEqual(3, canonical.filter { $0 == "\n" }.count, "exactly three separators")

        // Digest of the whole string, computed with shasum -a 256 outside this codebase.
        // Deliberately NOT HTTPRequestSignature.bodyHashHex: the helper must not be its own
        // oracle. Straight CryptoKit + a local formatter.
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(shaOfWholeGETCanonical, digest)

        // And the bytes themselves, spelled out field by field.
        var expected = Data()
        expected.append(contentsOf: Array("GET".utf8));               expected.append(0x0A)
        expected.append(contentsOf: Array("/api/web/sessions".utf8)); expected.append(0x0A)
        expected.append(contentsOf: Array(shaZeroBytes.utf8));        expected.append(0x0A)
        expected.append(contentsOf: Array(ts.utf8))
        XCTAssertEqual(expected, bytes)
    }

    func testRevokePOSTSignsTheExactBodyBytesSent() {
        let body = Data(revokeBody.utf8)
        let canonical = HTTPRequestSignature.canonicalMessage(
            method: "POST", path: "/api/web/sessions/revoke", bodyData: body, timestamp: ts)

        XCTAssertEqual("POST\n/api/web/sessions/revoke\n\(shaRevokeBody)\n\(ts)", canonical)

        // revoke-all differs only by path, so its signature must differ too — otherwise a
        // captured single-revoke signature would authorise wiping every linked device.
        let all = HTTPRequestSignature.canonicalMessage(
            method: "POST", path: "/api/web/sessions/revoke-all", bodyData: body, timestamp: ts)
        XCTAssertNotEqual(all, canonical)
    }

    // MARK: - Rule 1: the query string is not signed

    func testQueryStringIsExcludedFromTheSignedPath() {
        // The real GET URL carries ?publicKey=<base64>. The server verifies against
        // (req.originalUrl || req.url).split('?')[0] — oshi_auth.js:244 — so the client signs
        // the bare path. Signing the full URL is the likeliest way to get this wrong, because
        // the URL is what the caller has in hand.
        let signed = HTTPRequestSignature.canonicalMessage(
            method: "GET", path: "/api/web/sessions", bodyData: nil, timestamp: ts)
        let withQuery = HTTPRequestSignature.canonicalMessage(
            method: "GET", path: "/api/web/sessions?publicKey=Ab%2Bc%3D", bodyData: nil, timestamp: ts)

        XCTAssertNotEqual(withQuery, signed)
        XCTAssertEqual("GET\n/api/web/sessions\n\(shaZeroBytes)\n\(ts)", signed)
    }

    // MARK: - Rule 4: field order

    func testTimestampIsLastAndBodyHashIsThird() {
        // The mutation this test was built to catch: swapping the last two fields. Both are
        // ASCII decimal/hex, so the result is well formed and the 401 reads like clock skew.
        let canonical = HTTPRequestSignature.canonicalMessage(
            method: "POST", path: "/api/web/sessions/revoke-all", bodyData: Data("x".utf8), timestamp: ts)
        let fields = canonical.components(separatedBy: "\n")

        XCTAssertEqual(4, fields.count)
        XCTAssertEqual("POST", fields[0])
        XCTAssertEqual("/api/web/sessions/revoke-all", fields[1])
        XCTAssertEqual(64, fields[2].count, "third field is the 64-char body hash")
        XCTAssertEqual(fields[2].lowercased(), fields[2], "rule 3: lowercase hex")
        XCTAssertEqual(ts, fields[3], "fourth and last field is the millisecond timestamp")

        // Explicitly reject the transposed form.
        XCTAssertNotEqual("POST\n/api/web/sessions/revoke-all\n\(ts)\n\(fields[2])", canonical)
    }

    // MARK: - Rule 3 + the timestamp unit

    func testBodyHashIsLowercaseHexAndSixtyFourChars() {
        let hex = HTTPRequestSignature.bodyHashHex(nil)
        XCTAssertEqual(shaZeroBytes, hex)
        XCTAssertEqual(64, hex.count)
        XCTAssertTrue(hex.allSatisfy { $0.isHexDigit && !$0.isUppercase })
    }

    func testTimestampIsMillisecondsNotSeconds() {
        // Seconds would sit inside the server's 30s window only in 1970 and be rejected
        // forever after, so the unit is load-bearing. 13 digits from 2001 to 2286.
        let now = HTTPRequestSignature.currentTimestampMillis()
        XCTAssertEqual(13, now.count, "epoch milliseconds is 13 digits: \(now)")
        guard let ms = Double(now) else { return XCTFail("not a decimal string: \(now)") }
        XCTAssertEqual(Date().timeIntervalSince1970, ms / 1000, accuracy: 5,
                       "within the server's 30s skew window")
    }
}

// MARK: - docs/CALL_PUSH_AUTH_CONTRACT.md v1.0 §6 vectors
//
// __CALL_PUSH_AUTH_IOS_2026_09_23__ Every constant below is COPIED from the contract (produced
// by `node ServerPatches/call_push_auth_v2/test/vectors.js`, i.e. by the server's own Node
// crypto), never recomputed with the code under test. The requests are built the way the app
// builds them — a URLRequest with the real URL (query, trailing slash) and the body bytes —
// and go through the production signer `HTTPRequestSignature.sign(_:with:timestamp:)`.
//
// CryptoKit's Ed25519 is hedged (randomised), so its signature bytes are NOT compared with the
// contract's deterministic RFC 8032 ones; instead (a) the canonical string must be the
// contract's byte for byte, (b) the contract's signature must verify under the key derived
// from the contract's seed (same key, same message), and (c) our signature must verify too.
final class CallPushAuthContractVectorTests: XCTestCase {

    private let seedHex = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
    private let signingPubB64 = "ebVWLo/mVPlAeLES6KmLp5AfhTrmlb7X4OORC60ElmQ="
    private let identityB64 = "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s="
    private let identityB64url = "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s"
    private let ts = "1790000000000"
    private let emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private func key() throws -> Curve25519.Signing.PrivateKey {
        var bytes = [UInt8]()
        var i = seedHex.startIndex
        while i < seedHex.endIndex {
            let j = seedHex.index(i, offsetBy: 2)
            bytes.append(UInt8(seedHex[i..<j], radix: 16)!)
            i = j
        }
        return try Curve25519.Signing.PrivateKey(rawRepresentation: Data(bytes))
    }

    /// Signs `request` with the production helper and checks (a)(b)(c) + the headers.
    private func assertVector(_ request: URLRequest, canonical expected: String, contractSignature: String,
                              file: StaticString = #filePath, line: UInt = #line) throws {
        let k = try key()
        var req = request
        let canonical = HTTPRequestSignature.sign(&req, with: k, timestamp: ts)
        XCTAssertEqual(expected, canonical, "canonical string", file: file, line: line)

        XCTAssertEqual(signingPubB64, req.value(forHTTPHeaderField: "x-oshi-signing-pubkey"), file: file, line: line)
        XCTAssertEqual(ts, req.value(forHTTPHeaderField: "x-oshi-timestamp"), file: file, line: line)
        XCTAssertTrue(req.value(forHTTPHeaderField: "User-Agent")?.hasPrefix("OSHI-iOS/") == true, file: file, line: line)

        let msg = Data(expected.utf8)
        let contractSig = try XCTUnwrap(Data(base64Encoded: contractSignature), file: file, line: line)
        XCTAssertTrue(k.publicKey.isValidSignature(contractSig, for: msg),
                      "contract signature must verify under the seed's key", file: file, line: line)
        let ours = try XCTUnwrap(req.value(forHTTPHeaderField: "x-oshi-signature").flatMap { Data(base64Encoded: $0) },
                                 file: file, line: line)
        XCTAssertEqual(64, ours.count, file: file, line: line)
        XCTAssertTrue(k.publicKey.isValidSignature(ours, for: msg), "our signature must verify", file: file, line: line)
    }

    func testSeedDerivesTheContractSigningKey() throws {
        XCTAssertEqual(signingPubB64, try key().publicKey.rawRepresentation.base64EncodedString())
        XCTAssertEqual(identityB64url, HTTPRequestSignature.base64url(identityB64))
    }

    /// V1 — POST /api/call/signal, body bytes exactly as the contract gives them.
    func testV1_PostSignal() throws {
        let body = #"{"sender":"q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s","recipient":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","signal":"AA==","callId":"00000000-0000-4000-8000-000000000001","type":"callEnd"}"#
        XCTAssertEqual("7eb060c7b241f3056a6070b998167d6f3063c3d7ea2da86a1da4f0b746602b81",
                       HTTPRequestSignature.bodyHashHex(Data(body.utf8)))
        var r = URLRequest(url: URL(string: "https://oshi-messenger.com/api/call/signal")!)
        r.httpMethod = "POST"
        r.httpBody = Data(body.utf8)
        try assertVector(r,
            canonical: "POST\n/api/call/signal\n7eb060c7b241f3056a6070b998167d6f3063c3d7ea2da86a1da4f0b746602b81\n1790000000000",
            contractSignature: "t40jSdP7dJC4Tfa6E0+6j6m4LX9tCbtuYf6Jc6ryS4cCcPDzOuW/AGb2nwIvDyuC48uTwno0oWxfMHnkmdW2CQ==")
    }

    /// V2 — GET /api/call/signals/<me>?deviceId=dev-1: the query is NOT signed, GET hashes nothing.
    func testV2_GetSignalsExcludesQuery() throws {
        let me = HTTPRequestSignature.base64url(identityB64)
        var r = URLRequest(url: URL(string: "https://oshi-messenger.com/api/call/signals/\(me)?deviceId=dev-1")!)
        r.httpMethod = "GET"
        try assertVector(r,
            canonical: "GET\n/api/call/signals/q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s\n\(emptySha)\n1790000000000",
            contractSignature: "AfMWbSytNqos3cJ8kiJO2Vh84C7zIVOiHNhQZAG0tr8yZ3RQbu1ZFb0Vp78clm0EV9FB+Qj3bXAkQA7YCobUDg==")
    }

    /// V3 — WebSocket upgrade to wss://…/voip/ : trailing slash KEPT (url.path would drop it),
    /// no httpMethod set on a fresh URLRequest → signed as GET.
    func testV3_WebSocketUpgradeKeepsTrailingSlash() throws {
        let url = URL(string: "wss://oshi-messenger.com/voip/")!
        XCTAssertEqual("/voip", url.path, "why url.path is not used: it drops the slash")
        XCTAssertEqual("/voip/", HTTPRequestSignature.signingPath(for: url))
        try assertVector(URLRequest(url: url),
            canonical: "GET\n/voip/\n\(emptySha)\n1790000000000",
            contractSignature: "igxm0cDqwPYMtl7uE98/RLHFkyol29VKmoMiFEv+nqJz+N6vnHOBuB8b+Htg8Mcsqv0Wt9nTRzIKY3e8NvfBCQ==")
    }

    /// V4 — POST /push/unregister-device (identity in the body, standard base64 padded).
    func testV4_PushUnregisterDevice() throws {
        let body = #"{"publicKey":"q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s=","deviceId":"dev-1"}"#
        XCTAssertEqual("159004baa70dfde3466d8846958f44260c76cdbf79853c4d4179be14175dcaad",
                       HTTPRequestSignature.bodyHashHex(Data(body.utf8)))
        var r = URLRequest(url: URL(string: "https://oshi-messenger.com/push/unregister-device")!)
        r.httpMethod = "POST"
        r.httpBody = Data(body.utf8)
        try assertVector(r,
            canonical: "POST\n/push/unregister-device\n159004baa70dfde3466d8846958f44260c76cdbf79853c4d4179be14175dcaad\n1790000000000",
            contractSignature: "M5hYgsOthXPkLqfvNK4yZfjW1vE5CNA1Puz8I/oXMWeJZ6ydy9FWtZsqDssG3xA86yzboJ1AsPqTyA1EnytoAQ==")
    }

    /// The prefix iOS actually uses for call_server is `/voip` (`vpsBaseURL`), including the
    /// odd `/voip/api/call/end`: the path is signed AS SENT (contract §2, §3 row 3).
    func testVoipPrefixedPathsAreSignedAsSent() {
        XCTAssertEqual("/voip/api/call/end",
                       HTTPRequestSignature.signingPath(for: URL(string: "https://oshi-messenger.com/voip/api/call/end")!))
        XCTAssertEqual("/voip/signals/\(identityB64url)",
                       HTTPRequestSignature.signingPath(for: URL(string: "https://oshi-messenger.com/voip/signals/\(identityB64url)?deviceId=x")!))
        // A percent-escape stays escaped (server verifies req.originalUrl as received).
        XCTAssertEqual("/push/a%2Bb", HTTPRequestSignature.signingPath(for: URL(string: "https://h/push/a%2Bb")!))
    }
}


// MARK: - docs/CALL_MEDIA_RELAY_AUTH_CONTRACT.md v1.0 §7 (T1–T3)
//
// __CALL_MEDIA_AUTH_IOS_2026_09_23__ T1 = the signed `POST /relay-token` (body bytes built by
// the production helper), T2/T3 = the ORA1 trailer built by `UDPRelayClient`'s wire helpers.
final class CallMediaRelayAuthContractVectorTests: XCTestCase {

    private let seedHex = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
    private let identityB64 = "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s="   // 32 × 0xAB, standard base64
    private let peerB64 = "zc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc0="     // 32 × 0xCD
    private let identityB64url = "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s"
    private let peerB64url = "zc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc0"
    private let callId = "00000000-0000-4000-8000-000000000001"

    private func hex(_ s: String) -> Data {
        var out = Data(); var i = s.startIndex
        while i < s.endIndex { let j = s.index(i, offsetBy: 2); out.append(UInt8(s[i..<j], radix: 16)!); i = j }
        return out
    }
    private func toHex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }

    private let t2Hex = "042b7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63302b713675727136757271367572713675727136757271367572713675727136757271367572713675727136732430303030303030302d303030302d343030302d383030302d3030303030303030303030314f524131000102030405060708090a0b0c0d0e0f0000000000000001efcd414ee50798f79de486aaad32cdee"
    private let t3Hex = "052b7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63334e7a63302b713675727136757271367572713675727136757271367572713675727136757271367572713675727136732430303030303030302d303030302d343030302d383030302d3030303030303030303030314f524131000102030405060708090a0b0c0d0e0f000000000000000218b0e57904a1ab0168acbcc9faf527f3"

    private var tokenId: Data { UDPRelayClient.base64urlDecode("AAECAwQFBgcICQoLDA0ODw")! }
    private var macKey: SymmetricKey { SymmetricKey(data: UDPRelayClient.base64urlDecode("ERERERERERERERERERERERERERERERERERERERERERE")!) }

    func testT1_RelayTokenRequestBodyAndSignature() throws {
        let body = VoiceCallManager.relayTokenRequestBody(me: identityB64, peer: peerB64, callId: callId)
        XCTAssertEqual(#"{"identity":"q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s","peer":"zc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc0","callId":"00000000-0000-4000-8000-000000000001"}"#,
                       String(data: body, encoding: .utf8))
        XCTAssertEqual("d7d96a32479808485bfa046c6f90958321a4efa2df1e1e995f321f0826ea9fa0",
                       HTTPRequestSignature.bodyHashHex(body))
        let key = try Curve25519.Signing.PrivateKey(rawRepresentation: hex(seedHex))
        var r = URLRequest(url: URL(string: "https://oshi-messenger.com/api/call/relay-token")!)
        r.httpMethod = "POST"; r.httpBody = body
        let canonical = HTTPRequestSignature.sign(&r, with: key, timestamp: "1790000000000")
        let expected = "POST\n/api/call/relay-token\nd7d96a32479808485bfa046c6f90958321a4efa2df1e1e995f321f0826ea9fa0\n1790000000000"
        XCTAssertEqual(expected, canonical)
        // Ed25519 (RFC 8032) is deterministic, but CryptoKit randomizes — verify both ways.
        let contractSig = Data(base64Encoded: "MMiJUgXMwpDFQR7ZDZRWFZ4TathkQYx2kBOSE8l67niXpMK9nASLpki4SBcIFA4ta9NYyIpenRqvfzxs9GZYCA==")!
        XCTAssertTrue(key.publicKey.isValidSignature(contractSig, for: Data(expected.utf8)))
        let ours = try XCTUnwrap(r.value(forHTTPHeaderField: "x-oshi-signature").flatMap { Data(base64Encoded: $0) })
        XCTAssertTrue(key.publicKey.isValidSignature(ours, for: Data(expected.utf8)))
        // iOS actually sends it as /voip/relay-token, signed as sent.
        XCTAssertEqual("/voip/relay-token", HTTPRequestSignature.signingPath(for: URL(string: "https://oshi-messenger.com/voip/relay-token")!))
    }

    func testT2_AuthenticatedRegisterCounter1() {
        let header = UDPRelayClient.buildFrame(type: 0x04, recipient: UDPRelayClient.base64urlEncode(peerB64),
                                               sender: UDPRelayClient.base64urlEncode(identityB64),
                                               callId: callId, payload: Data())
        let dgram = UDPRelayClient.appendTrailer(to: header, tokenId: tokenId, counter: 1, macKey: macKey)
        XCTAssertEqual(170, dgram.count)
        XCTAssertEqual(t2Hex, toHex(dgram))
        XCTAssertEqual("efcd414ee50798f79de486aaad32cdee", toHex(dgram.suffix(16)))
    }

    func testT3_AuthenticatedKeepaliveCounter2() {
        let header = UDPRelayClient.buildFrame(type: 0x05, recipient: peerB64url, sender: identityB64url,
                                               callId: callId, payload: Data())
        let dgram = UDPRelayClient.appendTrailer(to: header, tokenId: tokenId, counter: 2, macKey: macKey)
        XCTAssertEqual(t3Hex, toHex(dgram))
        XCTAssertEqual("18b0e57904a1ab0168acbcc9faf527f3", toHex(dgram.suffix(16)))
    }

    func testTrailerLayoutIs44Bytes() {
        let header = UDPRelayClient.buildFrame(type: 0x04, recipient: peerB64url, sender: identityB64url,
                                               callId: callId, payload: Data())
        let d = UDPRelayClient.appendTrailer(to: header, tokenId: tokenId, counter: 0x0102030405060708, macKey: macKey)
        XCTAssertEqual(44, d.count - header.count)
        let t = d.suffix(44)
        XCTAssertEqual(Data("ORA1".utf8), Data(t.prefix(4)))
        XCTAssertEqual(tokenId, Data(t.dropFirst(4).prefix(16)))
        XCTAssertEqual(Data([1, 2, 3, 4, 5, 6, 7, 8]), Data(t.dropFirst(20).prefix(8)))   // u64 big-endian
    }

    func testNackParsingKeepsTheAckExactly0401() {
        XCTAssertNil(UDPRelayClient.parseNack(Data([0x04, 0x01])))
        for c: UInt8 in 1...6 { XCTAssertEqual(c, UDPRelayClient.parseNack(Data([0x04, 0x00, c]))) }
        XCTAssertNil(UDPRelayClient.parseNack(Data([0x01, 0x00, 0x01])))
        XCTAssertNil(UDPRelayClient.parseNack(Data([0x04, 0x00, 0x01, 0x00])))
    }

    func testTokenResponseParsing() throws {
        let json = #"{"tokenId":"AAECAwQFBgcICQoLDA0ODw","macKey":"ERERERERERERERERERERERERERERERERERERERERERE","expiresAt":1790014400000,"ttlMs":14400000,"udpPort":8089,"mode":"dual","v":1}"#
        let t = try XCTUnwrap(VoiceCallManager.parseRelayToken(Data(json.utf8)))
        XCTAssertEqual(tokenId, t.tokenId)
        XCTAssertEqual(1790014400, t.expiresAt.timeIntervalSince1970, accuracy: 0.001)
        XCTAssertEqual(Data(repeating: 0x11, count: 32), t.macKey.withUnsafeBytes { Data($0) })
        XCTAssertNil(VoiceCallManager.parseRelayToken(Data(#"{"tokenId":"AAEC","macKey":"EREx"}"#.utf8)))
    }
}
