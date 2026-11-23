//
//  BlindPushRegistrarTests.swift
//  OSHITests
//
//  __BLIND_PUSH_2026_11_09__ The registration decision against a fake push service and
//  gateway. Invariant checked everywhere: an APNs/VoIP token reaches the push service
//  ONLY on the legacy path, and the legacy body is exactly today's dictionary.
//

import XCTest
@testable import OSHI

final class BlindPushRegistrarTests: XCTestCase {

    private let base = "https://oshi-messenger.com/push"
    private let apns = String(repeating: "a1", count: 32)
    private let voip = String(repeating: "b2", count: 32)
    private let hM = "ph1." + String(repeating: "M", count: 420)
    private let hV = "ph1." + String(repeating: "V", count: 420)

    private var legacy: [String: Any] {
        ["publicKey": "PK", "deviceId": "dev", "voipToken": voip, "apnsToken": apns, "platform": "ios",
         "voipEnabled": true, "isChina": false, "apnsEnvironment": "production", "appVersion": "1.0.44"]
    }
    private var tokens: [BlindPushRegistrar.Token] {
        [.init(kind: .message, token: apns, env: "production"), .init(kind: .voip, token: voip, env: "production")]
    }

    /// Fake server: path → (status, body). `register` may be a queue.
    final class Fake {
        var routes: [String: (Int, String)] = [:]
        var registerQueue: [(Int, String)] = []
        var pushRequests: [URLRequest] = []
        var sealRequests: [URLRequest] = []
        var down = Set<String>()

        func push(_ r: URLRequest) -> BlindPushRegistrar.HTTPResult? {
            pushRequests.append(r)
            let p = r.url!.path
            if down.contains(p) { return nil }
            if p == "/push/register-device", !registerQueue.isEmpty {
                let (s, b) = registerQueue.removeFirst(); return .init(status: s, body: Data(b.utf8))
            }
            guard let (s, b) = routes[p] else { return .init(status: 404, body: Data()) }
            return .init(status: s, body: Data(b.utf8))
        }
        func seal(_ r: URLRequest) -> BlindPushRegistrar.HTTPResult? {
            sealRequests.append(r)
            if down.contains(r.url!.path) { return nil }
            guard let (s, b) = routes[r.url!.path] else { return .init(status: 404, body: Data()) }
            return .init(status: s, body: Data(b.utf8))
        }
        func registerBodies() -> [[String: Any]] {
            pushRequests.filter { $0.url!.path == "/push/register-device" }
                .map { (try? JSONSerialization.jsonObject(with: $0.httpBody ?? Data())) as? [String: Any] ?? [:] }
        }
        func capsOn() { routes["/push/capabilities"] = (200, #"{"blindPush":{"v":1,"sealUrl":"/push-gateway/v1/seal"}}"#) }
    }

    private func sealOK(_ f: Fake) {
        f.routes["/push-gateway/v1/seal"] = (200, #"{"handles":[{"platform":"apns","kind":"message","handle":"\#(hM)"},{"platform":"apns","kind":"voip","handle":"\#(hV)"}]}"#)
    }

    private func run(_ f: Fake, active: Bool, tokens: [BlindPushRegistrar.Token]? = nil, payload: [String: Any]? = nil) async -> (BlindPushRegistrar.Outcome, Bool) {
        var flag = active
        let o = await BlindPushRegistrar.register(
            pushBaseURL: base, legacyPayload: payload ?? legacy, tokens: tokens ?? self.tokens,
            cachedActive: active, setActive: { flag = $0 },
            pushSend: { f.push($0) }, sealSend: { f.seal($0) })
        return (o, flag)
    }

    private func assertLegacy(_ body: [String: Any], file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(NSDictionary(dictionary: body), NSDictionary(dictionary: legacy), file: file, line: line)
    }

    func testOldServerGetsTodaysBodyAndNoSeal() async {
        let f = Fake()
        f.routes["/push/register-device"] = (200, #"{"success":true}"#)
        let (o, active) = await run(f, active: false)
        XCTAssertEqual(o, .legacyRegistered)
        XCTAssertFalse(active)
        XCTAssertTrue(f.sealRequests.isEmpty)
        XCTAssertEqual(f.registerBodies().count, 1)
        assertLegacy(f.registerBodies()[0])
    }

    func testBlindServerTokensGoToGatewayOnly() async throws {
        let f = Fake(); f.capsOn(); sealOK(f)
        f.routes["/push/register-device"] = (200, #"{"success":true,"blindPush":{"supported":true,"accepted":true}}"#)
        let (o, active) = await run(f, active: false)
        XCTAssertEqual(o, .blindAccepted)
        XCTAssertTrue(active)
        XCTAssertEqual(f.sealRequests.first?.url?.absoluteString, "https://oshi-messenger.com/push-gateway/v1/seal")
        let sealBody = String(data: f.sealRequests[0].httpBody!, encoding: .utf8)!
        XCTAssertTrue(sealBody.contains(apns) && sealBody.contains(voip))
        XCTAssertFalse(sealBody.contains("PK"), "the seal request must not name the identity")
        XCTAssertNil(f.sealRequests[0].value(forHTTPHeaderField: "X-OSHI-Signature"))
        let reg = try XCTUnwrap(f.registerBodies().first)
        XCTAssertEqual(reg["apnsHandle"] as? String, hM)
        XCTAssertEqual(reg["voipHandle"] as? String, hV)
        XCTAssertNil(reg["apnsToken"]); XCTAssertNil(reg["voipToken"])
        XCTAssertEqual(reg["isChina"] as? Bool, false)
        for r in f.pushRequests {
            let b = String(data: r.httpBody ?? Data(), encoding: .utf8) ?? ""
            XCTAssertFalse(b.contains(apns) || b.contains(voip), "token sent to the push service")
        }
    }

    func testVoipOffSealsOnlyTheApnsToken() async throws {
        let f = Fake(); f.capsOn()
        f.routes["/push-gateway/v1/seal"] = (200, #"{"handles":[{"platform":"apns","kind":"message","handle":"\#(hM)"}]}"#)
        f.routes["/push/register-device"] = (200, #"{"blindPush":{"supported":true,"accepted":true}}"#)
        var p = legacy; p["voipToken"] = ""; p["voipEnabled"] = false
        let (o, _) = await run(f, active: false, tokens: [.init(kind: .message, token: apns, env: "sandbox")], payload: p)
        XCTAssertEqual(o, .blindAccepted)
        let sealBody = String(data: f.sealRequests[0].httpBody!, encoding: .utf8)!
        XCTAssertFalse(sealBody.contains(voip)); XCTAssertTrue(sealBody.contains("sandbox"))
        let reg = try XCTUnwrap(f.registerBodies().first)
        XCTAssertNil(reg["voipHandle"]); XCTAssertNil(reg["voipToken"])
    }

    func testHandleNotAcceptedFallsBackToLegacyInSameAttempt() async {
        let f = Fake(); f.capsOn(); sealOK(f)
        f.registerQueue = [(200, #"{"success":true}"#), (200, #"{"success":true}"#)]   // old server ignores handles
        let (o, active) = await run(f, active: true)
        XCTAssertEqual(o, .legacyRegistered)
        XCTAssertFalse(active)
        XCTAssertEqual(f.registerBodies().count, 2)
        assertLegacy(f.registerBodies()[1])
    }

    func testAlreadyBlindGatewayDownSendsNoToken() async {
        let f = Fake(); f.capsOn(); f.down.insert("/push-gateway/v1/seal")
        let (o, active) = await run(f, active: true)
        XCTAssertEqual(o, .retryLater)
        XCTAssertTrue(active)
        XCTAssertTrue(f.registerBodies().isEmpty)
    }

    func testAlreadyBlindRegister503SendsNoToken() async {
        let f = Fake(); f.capsOn(); sealOK(f)
        f.routes["/push/register-device"] = (503, #"{"error":"gateway-unavailable","blindPush":{"supported":true,"accepted":false}}"#)
        let (o, _) = await run(f, active: true)
        XCTAssertEqual(o, .retryLater)
        XCTAssertEqual(f.registerBodies().count, 1)
        XCTAssertNil(f.registerBodies()[0]["apnsToken"])
    }

    func testNeverBlindGatewayDownStaysLegacy() async {
        let f = Fake(); f.capsOn(); f.routes["/push-gateway/v1/seal"] = (500, "")
        f.routes["/push/register-device"] = (200, #"{"success":true}"#)
        let (o, _) = await run(f, active: false)
        XCTAssertEqual(o, .legacyRegistered)
        assertLegacy(f.registerBodies()[0])
    }

    func testIncompleteSealReplyIsRejected() async {
        let f = Fake(); f.capsOn()
        f.routes["/push-gateway/v1/seal"] = (200, #"{"handles":[{"platform":"apns","kind":"message","handle":"\#(hM)"}]}"#)  // VoIP missing
        f.routes["/push/register-device"] = (200, #"{"success":true}"#)
        let (o, _) = await run(f, active: false)
        XCTAssertEqual(o, .legacyRegistered, "a half-sealed registration would silently drop the VoIP token")
    }

    func testCapsUnreachableFollowsRememberedState() async {
        let f = Fake(); f.down.insert("/push/capabilities"); sealOK(f)
        f.routes["/push/register-device"] = (200, #"{"blindPush":{"supported":true,"accepted":true}}"#)
        let (o1, _) = await run(f, active: true)
        XCTAssertEqual(o1, .blindAccepted)
        let g = Fake(); g.down.insert("/push/capabilities"); g.routes["/push/register-device"] = (200, "{}")
        let (o2, _) = await run(g, active: false)
        XCTAssertEqual(o2, .legacyRegistered)
        XCTAssertTrue(g.sealRequests.isEmpty)
    }

    func testServerStopsAdvertisingClearsFlag() async {
        let f = Fake(); f.routes["/push/capabilities"] = (200, #"{"blindPush":null}"#)
        f.routes["/push/register-device"] = (200, "{}")
        let (o, active) = await run(f, active: true)
        XCTAssertEqual(o, .legacyRegistered)
        XCTAssertFalse(active)
    }

    func testSealURLResolution() {
        XCTAssertEqual(BlindPushRegistrar.resolveSealURL("/push-gateway/v1/seal", pushBaseURL: base)?.absoluteString, "https://oshi-messenger.com/push-gateway/v1/seal")
        XCTAssertEqual(BlindPushRegistrar.resolveSealURL("https://gw.example.net/v1/seal", pushBaseURL: base)?.absoluteString, "https://gw.example.net/v1/seal")
        XCTAssertNil(BlindPushRegistrar.resolveSealURL("http://gw.example.net/v1/seal", pushBaseURL: base))
        XCTAssertNil(BlindPushRegistrar.resolveSealURL("v1/seal", pushBaseURL: base))
        XCTAssertEqual(BlindPushRegistrar.parseCaps(status: 404, body: nil, pushBaseURL: base), .unsupported)
        XCTAssertEqual(BlindPushRegistrar.parseCaps(status: 502, body: nil, pushBaseURL: base), .unknown)
        XCTAssertEqual(BlindPushRegistrar.parseCaps(status: 200, body: Data(#"{"blindPush":{"v":2,"sealUrl":"/x"}}"#.utf8), pushBaseURL: base), .unsupported)
    }
}
