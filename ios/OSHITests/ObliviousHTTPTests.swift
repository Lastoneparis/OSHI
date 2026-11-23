//
//  ObliviousHTTPTests.swift
//  OSHITests
//
//  __OHTTP_PRIVACY_RELAY_2026_11_09__ Pins the Oblivious HTTP client to the
//  RFC 9458 Appendix A test vector and the Binary HTTP (RFC 9292) framing.
//
//  CryptoKit HPKE does not let a caller inject the sender's ephemeral key, so
//  the vector's encapsulated REQUEST cannot be reproduced byte-for-byte. What
//  is checked instead, all against the RFC's own bytes:
//    * key config parsing, §4.3 header and HPKE info;
//    * the vector's BHTTP request, byte-for-byte;
//    * the vector's encapsulated request opens with `HPKE.Recipient` (the
//      vector's gateway private key) to the vector's BHTTP request;
//    * the §4.4 response derivation (secret, PRK-derived key/nonce) reproduces
//      the vector's encapsulated response byte-for-byte, and our client-side
//      decapsulation opens it to `0140c8` (status 200);
//    * a request WE encapsulate opens with `HPKE.Recipient`, and a response the
//      recipient seals opens with our client context (full round-trip).
//

import XCTest
import CryptoKit
@testable import OSHI

final class ObliviousHTTPTests: XCTestCase {

    // RFC 9458 Appendix A
    static let vectorSecretKey = "3c168975674b2fa8e465970b79c8dcf09f1c741626480bd4c6162fc5b6a98e1a"
    static let vectorKeyConfig = "01002031e1f05a740102115220e9af918f738674aec95f54db6e04eb705aae8e79815500080001000100010003"
    static let vectorRequest = "00034745540568747470730b6578616d706c652e636f6d012f"
    static let vectorEphemeralPublic = "4b28f881333e7c164ffc499ad9796f877f4e1051ee6d31bad19dec96c208b472"
    static let vectorInfo = "6d6573736167652f626874747020726571756573740001002000010001"
    static let vectorEncapsulatedRequest = "010020000100014b28f881333e7c164ffc499ad9796f877f4e1051ee6d31bad19dec96c208b4726374e469135906992e1268c594d2a10c695d858c40a026e7965e7d86b83dd440b2c0185204b4d63525"
    static let vectorResponse = "0140c8"
    static let vectorExportedSecret = "62d87a6ba569ee81014c2641f52bea36"
    static let vectorResponseNonce = "c789e7151fcba46158ca84b04464910d"
    static let vectorAEADKey = "5d0172a080e428b16d298c4ea0db620d"
    static let vectorAEADNonce = "f6bf1aeb88d6df87007fa263"
    static let vectorEncapsulatedResponse = "c789e7151fcba46158ca84b04464910d86f9013e404feea014e7be4a441f234f857fbd"

    private func hex(_ s: String) -> Data { Data(ohttpHex: s)! }
    private func toHex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }

    // MARK: varints

    func testVarintRFC9000Examples() throws {
        // RFC 9000 §A.1 sample encodings.
        let cases: [(UInt64, String)] = [
            (151_288_809_941_952_652, "c2197c5eff14e88c"),
            (494_878_333, "9d7f3e7d"),
            (15_293, "7bbd"),
            (37, "25"),
        ]
        for (value, encoded) in cases {
            XCTAssertEqual(toHex(QUICVarint.encode(value)), encoded)
            var r = OHTTPByteReader(hex(encoded))
            XCTAssertEqual(try r.readVarint(), value)
            XCTAssertTrue(r.isAtEnd)
        }
        // Non-minimal 2-byte encoding of 37 still decodes (RFC 9000 §A.1).
        var r = OHTTPByteReader(hex("4025"))
        XCTAssertEqual(try r.readVarint(), 37)
    }

    func testVarintBoundaries() throws {
        for v: UInt64 in [0, 63, 64, 16383, 16384, (1 << 30) - 1, 1 << 30, QUICVarint.max] {
            var r = OHTTPByteReader(QUICVarint.encode(v))
            XCTAssertEqual(try r.readVarint(), v)
            XCTAssertTrue(r.isAtEnd)
        }
        XCTAssertEqual(QUICVarint.encode(63).count, 1)
        XCTAssertEqual(QUICVarint.encode(64).count, 2)
        XCTAssertEqual(QUICVarint.encode(16384).count, 4)
        XCTAssertEqual(QUICVarint.encode(1 << 30).count, 8)
        var truncated = OHTTPByteReader(hex("80ff"))
        XCTAssertThrowsError(try truncated.readVarint())
    }

    // MARK: Binary HTTP

    func testBHTTPEncodesVectorRequest() throws {
        let req = BinaryHTTP.Request(method: "GET", scheme: "https", authority: "example.com", path: "/",
                                     headers: [], content: Data())
        XCTAssertEqual(toHex(BinaryHTTP.encode(req, truncate: true)), Self.vectorRequest)
        // Full (non-truncated) form = vector + empty headers/content/trailers.
        XCTAssertEqual(toHex(BinaryHTTP.encode(req)), Self.vectorRequest + "000000")
        XCTAssertEqual(try BinaryHTTP.decodeRequest(hex(Self.vectorRequest)), req)
        XCTAssertEqual(try BinaryHTTP.decodeRequest(BinaryHTTP.encode(req)), req)
    }

    func testBHTTPDecodesVectorResponse() throws {
        let resp = try BinaryHTTP.decodeResponse(hex(Self.vectorResponse))
        XCTAssertEqual(resp.status, 200)
        XCTAssertTrue(resp.headers.isEmpty)
        XCTAssertTrue(resp.content.isEmpty)
    }

    func testBHTTPRequestRoundTripWithHeadersAndBody() throws {
        let body = Data((0..<300).map { UInt8($0 & 0xff) })
        let req = BinaryHTTP.Request(method: "POST", scheme: "https", authority: "oshi-messenger.com",
                                     path: "/v2/messages/abc%2Bdef%3D?after=12",
                                     headers: [("content-type", "application/json"),
                                               ("x-oshi-signature", "c2lnbmF0dXJl"),
                                               ("x-oshi-timestamp", "1762646400000")],
                                     content: body)
        let wire = BinaryHTTP.encode(req)
        XCTAssertEqual(wire.first, 0x00)
        XCTAssertEqual(try BinaryHTTP.decodeRequest(wire), req)
        // RFC 9292 §3.8 padding is ignored.
        XCTAssertEqual(try BinaryHTTP.decodeRequest(wire + Data(count: 17)), req)
        // Non-zero trailing bytes are not.
        XCTAssertThrowsError(try BinaryHTTP.decodeRequest(wire + Data([1])))
    }

    func testBHTTPUppercaseHeaderNamesAreLowercasedOnTheWire() throws {
        let req = BinaryHTTP.Request(method: "GET", scheme: "https", authority: "a", path: "/",
                                     headers: [("Content-Type", "x")], content: Data())
        XCTAssertEqual(try BinaryHTTP.decodeRequest(BinaryHTTP.encode(req)).headers.first?.name, "content-type")
    }

    func testBHTTPResponseRoundTripAndInformationalSkipped() throws {
        let resp = BinaryHTTP.Response(status: 404, headers: [("content-type", "application/json")],
                                       content: Data("{\"error\":\"nope\"}".utf8))
        XCTAssertEqual(try BinaryHTTP.decodeResponse(BinaryHTTP.encode(resp)), resp)

        // 1 | 103 Early Hints {link: </a>} | 100 Continue {} | final 200 …
        var wire = Data()
        QUICVarint.encode(1, into: &wire)
        QUICVarint.encode(103, into: &wire)
        var section = Data()
        QUICVarint.encode(4, into: &section); section.append(Data("link".utf8))
        QUICVarint.encode(4, into: &section); section.append(Data("</a>".utf8))
        QUICVarint.encode(UInt64(section.count), into: &wire); wire.append(section)
        QUICVarint.encode(100, into: &wire)
        QUICVarint.encode(0, into: &wire)
        let final = BinaryHTTP.Response(status: 200, headers: [("content-type", "text/plain")], content: Data("ok".utf8))
        wire.append(BinaryHTTP.encode(final).dropFirst())   // drop the framing indicator
        XCTAssertEqual(try BinaryHTTP.decodeResponse(wire), final)
    }

    func testBHTTPRejectsWrongFramingAndBadStatus() {
        XCTAssertThrowsError(try BinaryHTTP.decodeResponse(hex(Self.vectorRequest)))   // indicator 0
        XCTAssertThrowsError(try BinaryHTTP.decodeRequest(hex(Self.vectorResponse)))   // indicator 1
        XCTAssertThrowsError(try BinaryHTTP.decodeResponse(hex("0300")))               // indefinite-length
        XCTAssertThrowsError(try BinaryHTTP.decodeResponse(hex("0142bc")))             // 700
        XCTAssertThrowsError(try BinaryHTTP.decodeResponse(hex("01")))                 // no status
    }

    // MARK: Key config

    func testParsesVectorKeyConfigHeaderAndInfo() throws {
        let cfg = try OHTTPKeyConfig.parse(hex(Self.vectorKeyConfig))
        XCTAssertEqual(cfg.keyID, 1)
        XCTAssertEqual(cfg.kemID, 0x0020)
        XCTAssertEqual(toHex(cfg.publicKey), "31e1f05a740102115220e9af918f738674aec95f54db6e04eb705aae8e798155")
        XCTAssertEqual(cfg.symmetric.map { [$0.kdf, $0.aead] }, [[1, 1], [1, 3]])
        XCTAssertTrue(cfg.supportsOSHISuite)
        XCTAssertEqual(toHex(cfg.requestHeader), "01002000010001")
        XCTAssertEqual(toHex(cfg.requestInfo), Self.vectorInfo)
        // The vector's encapsulated request starts with exactly this header + enc.
        XCTAssertTrue(Self.vectorEncapsulatedRequest.hasPrefix(toHex(cfg.requestHeader) + Self.vectorEphemeralPublic))
    }

    func testParsesOhttpKeysListAndSelects() throws {
        let one = hex(Self.vectorKeyConfig)
        // A config for an unknown KEM (0x0010, P-256) we must skip, then ours.
        var other = Data([7, 0x00, 0x10]); other.append(Data(count: 65)); other.append(contentsOf: [0, 4, 0, 1, 0, 1])
        var list = Data()
        for c in [other, one] { list.append(UInt8(c.count >> 8)); list.append(UInt8(c.count & 0xff)); list.append(c) }
        let parsed = try OHTTPKeyConfig.parseList(list)
        XCTAssertEqual(parsed.count, 1)
        XCTAssertEqual(OHTTPKeyConfig.select(from: parsed)?.keyID, 1)
        XCTAssertEqual(try OHTTPKeyConfig.parseAny(list), parsed)
        XCTAssertEqual(try OHTTPKeyConfig.parseAny(one), parsed)
        XCTAssertThrowsError(try OHTTPKeyConfig.parse(one + Data([0])))
        XCTAssertThrowsError(try OHTTPKeyConfig.parse(one.prefix(20)))
    }

    func testSelectRejectsConfigWithoutAES128GCM() throws {
        var bytes = [UInt8](hex(Self.vectorKeyConfig))
        // Rewrite the suite list to only HKDF-SHA256/ChaCha20Poly1305 (0x0003).
        bytes.replaceSubrange((bytes.count - 8)..<bytes.count, with: [0, 1, 0, 3, 0, 1, 0, 3])
        let cfg = try OHTTPKeyConfig.parse(Data(bytes))
        XCTAssertFalse(cfg.supportsOSHISuite)
        XCTAssertNil(OHTTPKeyConfig.select(from: [cfg]))
    }

    // MARK: Encapsulation — RFC 9458 Appendix A

    @available(iOS 17.0, macOS 14.0, *)
    private func vectorRecipient(enc: Data) throws -> HPKE.Recipient {
        let sk = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: hex(Self.vectorSecretKey))
        let cfg = try OHTTPKeyConfig.parse(hex(Self.vectorKeyConfig))
        return try HPKE.Recipient(privateKey: sk, ciphersuite: OHTTP.ciphersuite,
                                  info: cfg.requestInfo, encapsulatedKey: enc)
    }

    func testVectorRequestDecapsulatesAndResponseMatchesByteForByte() throws {
        guard #available(iOS 17.0, macOS 14.0, *) else { throw XCTSkip("HPKE needs iOS 17") }
        let encReq = hex(Self.vectorEncapsulatedRequest)
        let enc = encReq.subdata(in: 7..<39)
        XCTAssertEqual(toHex(enc), Self.vectorEphemeralPublic)
        var recipient = try vectorRecipient(enc: enc)
        let plaintext = try recipient.open(encReq.subdata(in: 39..<encReq.count))
        XCTAssertEqual(toHex(plaintext), Self.vectorRequest)

        let secret = try recipient.exportSecret(context: Data(OHTTP.responseLabel.utf8), outputByteCount: 16)
            .withUnsafeBytes { Data($0) }
        XCTAssertEqual(toHex(secret), Self.vectorExportedSecret)

        let (key, nonce) = OHTTP.responseKeyAndNonce(secret: secret, enc: enc, responseNonce: hex(Self.vectorResponseNonce))
        XCTAssertEqual(toHex(key.withUnsafeBytes { Data($0) }), Self.vectorAEADKey)
        XCTAssertEqual(toHex(nonce), Self.vectorAEADNonce)

        let sealed = try OHTTP.encapsulateResponse(hex(Self.vectorResponse), secret: secret, enc: enc,
                                                   responseNonce: hex(Self.vectorResponseNonce))
        XCTAssertEqual(toHex(sealed), Self.vectorEncapsulatedResponse)

        // Client side: open the vector's response with the vector's context.
        let opened = try OHTTP.decapsulateResponse(hex(Self.vectorEncapsulatedResponse),
                                                   context: .init(enc: enc, exportedSecret: secret))
        XCTAssertEqual(toHex(opened), Self.vectorResponse)
        XCTAssertEqual(try BinaryHTTP.decodeResponse(opened).status, 200)

        // A flipped bit in the response must fail closed.
        var tampered = hex(Self.vectorEncapsulatedResponse); tampered[tampered.count - 1] ^= 1
        XCTAssertThrowsError(try OHTTP.decapsulateResponse(tampered, context: .init(enc: enc, exportedSecret: secret)))
    }

    func testOurEncapsulationRoundTripsThroughAnHPKERecipient() throws {
        guard #available(iOS 17.0, macOS 14.0, *) else { throw XCTSkip("HPKE needs iOS 17") }
        let cfg = try OHTTPKeyConfig.parse(hex(Self.vectorKeyConfig))
        let inner = BinaryHTTP.Request(method: "GET", scheme: "https", authority: "oshi-messenger.com",
                                       path: "/v2/config", headers: [("accept", "application/json")], content: Data())
        let (encapsulated, ctx) = try OHTTP.encapsulateRequest(BinaryHTTP.encode(inner), config: cfg)
        XCTAssertEqual(toHex(encapsulated.prefix(7)), "01002000010001")
        let enc = encapsulated.subdata(in: 7..<39)
        XCTAssertEqual(enc, ctx.enc)

        // Gateway side.
        var recipient = try vectorRecipient(enc: enc)
        let opened = try recipient.open(encapsulated.subdata(in: 39..<encapsulated.count))
        XCTAssertEqual(try BinaryHTTP.decodeRequest(opened), inner)
        let secret = try recipient.exportSecret(context: Data(OHTTP.responseLabel.utf8), outputByteCount: 16)
            .withUnsafeBytes { Data($0) }
        XCTAssertEqual(secret, ctx.exportedSecret)

        let reply = BinaryHTTP.Response(status: 200, headers: [("content-type", "application/json")],
                                        content: Data("{\"ok\":true}".utf8))
        var nonce = Data(count: 16); for i in 0..<16 { nonce[i] = UInt8.random(in: 0...255) }
        let sealed = try OHTTP.encapsulateResponse(BinaryHTTP.encode(reply), secret: secret, enc: enc, responseNonce: nonce)
        XCTAssertEqual(try BinaryHTTP.decodeResponse(OHTTP.decapsulateResponse(sealed, context: ctx)), reply)
    }

    // MARK: Inner request / routing

    func testInnerRequestCarriesSignedPathHeadersAndBody() throws {
        var req = URLRequest(url: URL(string: "https://oshi-messenger.com/v2/messages/ab%2Fc%3D?after=5")!)
        req.httpMethod = "POST"
        req.httpBody = Data("{}".utf8)
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("sig", forHTTPHeaderField: "x-oshi-signature")
        req.setValue("oshi-messenger.com", forHTTPHeaderField: "Host")
        let inner = try OHTTPTransport.innerRequest(for: req)
        XCTAssertEqual(inner.method, "POST")
        XCTAssertEqual(inner.scheme, "https")
        XCTAssertEqual(inner.authority, "oshi-messenger.com")
        XCTAssertEqual(inner.path, "/v2/messages/ab%2Fc%3D?after=5")   // the exact signed path, still encoded
        XCTAssertEqual(inner.content, Data("{}".utf8))
        let names = inner.headers.map(\.name)
        XCTAssertTrue(names.contains("content-type"))
        XCTAssertTrue(names.contains("x-oshi-signature"))
        XCTAssertFalse(names.contains("host"))
    }

    func testEveryV2PathIsRoutableIncludingBlobs() {
        XCTAssertTrue(OHTTPSettings.isRoutable(path: "/v2/messages"))
        XCTAssertTrue(OHTTPSettings.isRoutable(path: "/v2/keys/abc/count"))
        // __OHTTP_MEDIA_2026_11_10__ the phase-1 `/v2/blobs*` exclusion is gone.
        XCTAssertTrue(OHTTPSettings.isRoutable(path: "/v2/blobs"))
        XCTAssertTrue(OHTTPSettings.isRoutable(path: "/v2/blobs/x/chunk/0"))
        XCTAssertFalse(OHTTPSettings.isRoutable(path: "/api/queue/x"))
    }

    func testNoSilentFallbackWhenNotConfigured() async {
        let req = URLRequest(url: URL(string: "https://oshi-messenger.com/v2/config")!)
        do {
            _ = try await OHTTPTransport.data(for: req, session: .shared, relayURL: nil, keyConfig: nil)
            XCTFail("must throw, never go direct")
        } catch {
            XCTAssertEqual(error as? OHTTPError, .notConfigured)
        }
    }

    func testRelayFailureIsAnErrorNotADirectRequest() async throws {
        guard #available(iOS 17.0, macOS 14.0, *) else { throw XCTSkip("HPKE needs iOS 17") }
        let cfg = try OHTTPKeyConfig.parse(hex(Self.vectorKeyConfig))
        // Nothing listens on port 9 — the call must fail, not reach the target.
        let req = URLRequest(url: URL(string: "https://oshi-messenger.com/v2/config")!)
        do {
            _ = try await OHTTPTransport.data(for: req, session: .shared,
                                              relayURL: URL(string: "http://127.0.0.1:9/")!, keyConfig: cfg)
            XCTFail("must throw")
        } catch {
            XCTAssertNotNil(error as? URLError ?? (error as NSError?))
        }
    }

    func testPinnedKeyConfigParsesAndMatchesTheLiveGatewayKey() throws {
        let cfg = try XCTUnwrap(OHTTPSettings.pinnedKeyConfig)
        XCTAssertEqual(cfg.keyID, 0x01)
        XCTAssertTrue(cfg.supportsOSHISuite)
        // The served application/ohttp-keys form (2-byte length prefix) is the same key.
        let served = hex("0029" + OHTTPSettings.pinnedKeyConfigHex)
        XCTAssertEqual(OHTTPKeyConfig.select(from: try OHTTPKeyConfig.parseList(served)), cfg)
    }

    func testPlaceholdersKeepTheFeatureOffUntilFilledIn() {
        // While the relay URL / pinned key are placeholders, the toggle must be
        // disabled and V2Transport must stay on the direct path.
        if OHTTPSettings.relayURLString.isEmpty || OHTTPSettings.pinnedKeyConfigHex.isEmpty {
            if ProcessInfo.processInfo.environment["OSHI_OHTTP_RELAY_URL"] == nil {
                XCTAssertFalse(OHTTPSettings.isConfigured)
                XCTAssertFalse(OHTTPSettings.isActive)
            }
        } else {
            XCTAssertNotNil(OHTTPSettings.pinnedKeyConfig, "pinnedKeyConfigHex does not parse to an X25519/AES-128-GCM config")
        }
    }

    // MARK: Live (skipped unless the gateway + local test relay are up)

    func testLiveGetV2ConfigThroughTestRelay() async throws {
        guard #available(iOS 17.0, macOS 14.0, *) else { throw XCTSkip("HPKE needs iOS 17") }
        let relay = URL(string: ProcessInfo.processInfo.environment["OSHI_OHTTP_TEST_RELAY"] ?? "http://127.0.0.1:8787/")!
        var cfgReq = URLRequest(url: URL(string: "https://oshi-messenger.com/ohttp/config")!)
        cfgReq.timeoutInterval = 8
        guard let (cfgData, cfgResp) = try? await URLSession.shared.data(for: cfgReq),
              (cfgResp as? HTTPURLResponse)?.statusCode == 200,
              let list = try? OHTTPKeyConfig.parseAny(cfgData),
              let cfg = OHTTPKeyConfig.select(from: list) else {
            throw XCTSkip("gateway key config not live")
        }
        var req = URLRequest(url: URL(string: "https://oshi-messenger.com/v2/config")!)
        req.httpMethod = "GET"
        req.timeoutInterval = 15
        let data: Data, resp: URLResponse
        do {
            (data, resp) = try await OHTTPTransport.data(for: req, session: .shared, relayURL: relay, keyConfig: cfg)
        } catch let e as URLError where e.code == .cannotConnectToHost || e.code == .appTransportSecurityRequiresSecureConnection {
            throw XCTSkip("test relay not reachable: \(e.code.rawValue)")
        }
        XCTAssertEqual((resp as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertNotNil(try? JSONSerialization.jsonObject(with: data))
    }

    // __OHTTP_RELAY_ADDRESS_2026_11_10__ what a user may enter as the relay.
    func testRelayAddressValidation() {
        XCTAssertEqual(OHTTPSettings.normalizeRelayURL(" https://relay.example.org/ohttp "), "https://relay.example.org/ohttp")
        XCTAssertEqual(OHTTPSettings.normalizeRelayURL("https://relay.example.org"), "https://relay.example.org/")
        XCTAssertNil(OHTTPSettings.normalizeRelayURL("http://relay.example.org/"), "cleartext")
        XCTAssertNil(OHTTPSettings.normalizeRelayURL("https://oshi-messenger.com/ohttp/gateway"), "OSHI itself")
        XCTAssertNil(OHTTPSettings.normalizeRelayURL("https://relay.oshi-messenger.com/"), "OSHI subdomain")
        XCTAssertNil(OHTTPSettings.normalizeRelayURL(""))
        XCTAssertNil(OHTTPSettings.normalizeRelayURL("https://localhost/"))
    }
}
