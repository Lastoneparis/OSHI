//
//  BlindPushRegistrar.swift
//  OSHI
//
//  __BLIND_PUSH_2026_11_09__ Register this device's APNs and PushKit tokens as opaque
//  HANDLES when the push service advertises blind push.
//
//  The push service used to store `identity -> APNs token, VoIP token`. With blind push,
//  the tokens go to the push GATEWAY (`POST <sealUrl>`, UNSIGNED, no identity in it),
//  which returns handles sealed under a key only the gateway holds; `/push/register-device`
//  then receives `apnsHandle` / `voipHandle` instead of `apnsToken` / `voipToken`, and the
//  server drops the tokens it had for this device.
//
//  Compatibility:
//   * Server without the feature (today's prod): `GET /push/capabilities` → 404 → the
//     legacy registration, the very dictionary 1.0.43 sends.
//   * Handle registration answered without `blindPush.accepted` (old server, feature
//     switched off) → legacy registration in the same attempt.
//   * Already blind and the gateway / network fails → NO fallback to the token: the
//     handle registered earlier keeps working; the caller retries later.
//
//  Pure logic + an injected transport, so it is unit-tested without a network.
//

import Foundation

enum BlindPushRegistrar {

    enum Caps: Equatable {
        case supported(sealURL: URL)
        case unsupported
        case unknown
    }

    enum Outcome: Equatable {
        case blindAccepted
        case legacyRegistered
        case retryLater
        case failed
    }

    struct Token: Equatable {
        enum Kind: String { case message, voip }
        let kind: Kind
        let token: String
        let env: String          // "production" | "sandbox"
    }

    struct HTTPResult {
        let status: Int
        let body: Data?
    }

    /// nil = network error.
    typealias Send = (URLRequest) async -> HTTPResult?

    static let handlePrefix = "ph1."
    static let activeDefaultsKey = "blindPushActive"

    // MARK: - Pure helpers

    static func parseCaps(status: Int, body: Data?, pushBaseURL: String) -> Caps {
        if status == 404 { return .unsupported }
        guard (200..<300).contains(status), let body,
              let obj = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else { return .unknown }
        guard let bp = obj["blindPush"] as? [String: Any] else { return .unsupported }
        guard (bp["v"] as? Int) == 1,
              let raw = bp["sealUrl"] as? String,
              let url = resolveSealURL(raw, pushBaseURL: pushBaseURL) else { return .unsupported }
        return .supported(sealURL: url)
    }

    /// Relative → same origin as the push base. Absolute → https only (plain http only
    /// when the push base itself is plain http: local test servers).
    static func resolveSealURL(_ sealURL: String, pushBaseURL: String) -> URL? {
        guard !sealURL.isEmpty, let base = URL(string: pushBaseURL), let scheme = base.scheme, let host = base.host else { return nil }
        if sealURL.hasPrefix("https://") { return URL(string: sealURL) }
        if sealURL.hasPrefix("http://") { return scheme == "http" ? URL(string: sealURL) : nil }
        guard sealURL.hasPrefix("/") else { return nil }
        let port = base.port.map { ":\($0)" } ?? ""
        return URL(string: "\(scheme)://\(host)\(port)\(sealURL)")
    }

    static func shouldUseBlind(_ caps: Caps, cachedActive: Bool) -> Bool {
        switch caps {
        case .supported: return true
        case .unknown: return cachedActive
        case .unsupported: return false
        }
    }

    static func sealRequestBody(_ tokens: [Token]) -> Data? {
        let list: [[String: Any]] = tokens.map { ["token": $0.token, "platform": "apns", "kind": $0.kind.rawValue, "env": $0.env] }
        return try? JSONSerialization.data(withJSONObject: ["tokens": list])
    }

    /// kind → handle, only if EVERY requested kind came back as a well-formed handle.
    static func parseSealResponse(_ body: Data?, expected: [Token.Kind]) -> [Token.Kind: String]? {
        guard let body,
              let obj = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any],
              let arr = obj["handles"] as? [[String: Any]] else { return nil }
        var out: [Token.Kind: String] = [:]
        for h in arr {
            guard (h["platform"] as? String) == "apns",
                  let k = (h["kind"] as? String).flatMap(Token.Kind.init(rawValue:)),
                  let v = h["handle"] as? String, v.hasPrefix(handlePrefix), v.count < 4096 else { continue }
            out[k] = v
        }
        return Set(out.keys) == Set(expected) ? out : nil
    }

    /// The legacy payload with its token fields replaced by handle fields.
    static func blindPayload(from legacy: [String: Any], handles: [Token.Kind: String]) -> [String: Any] {
        var p = legacy
        p.removeValue(forKey: "apnsToken")
        p.removeValue(forKey: "voipToken")
        if let h = handles[.message] { p["apnsHandle"] = h }
        if let h = handles[.voip] { p["voipHandle"] = h }
        return p
    }

    static func blindFlag(_ body: Data?, _ key: String) -> Bool {
        guard let body, let obj = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any],
              let bp = obj["blindPush"] as? [String: Any] else { return false }
        return (bp[key] as? Bool) == true
    }

    // MARK: - One registration attempt

    /// - Parameters:
    ///   - legacyPayload: exactly today's `/register-device` dictionary (tokens included).
    ///   - tokens: the tokens THIS registration may hand to the gateway (VoIP omitted when
    ///     VoIP is off / China mode, as `legacyPayload` blanks it).
    ///   - pushSend: signed requests to the push service.
    ///   - sealSend: UNSIGNED requests to the gateway.
    static func register(
        pushBaseURL: String,
        legacyPayload: [String: Any],
        tokens: [Token],
        cachedActive: Bool,
        setActive: (Bool) -> Void,
        pushSend: Send,
        sealSend: Send,
        log: (String) -> Void = { _ in }
    ) async -> Outcome {
        func post(_ url: URL, _ body: Data?, _ send: Send) async -> HTTPResult? {
            var r = URLRequest(url: url)
            r.httpMethod = "POST"
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = body
            r.timeoutInterval = 8
            return await send(r)
        }
        guard let registerURL = URL(string: "\(pushBaseURL)/register-device") else { return .failed }

        func legacy(_ why: String) async -> Outcome {
            guard let data = try? JSONSerialization.data(withJSONObject: legacyPayload) else { return .failed }
            let r = await post(registerURL, data, pushSend)
            let ok = r.map { (200..<300).contains($0.status) } ?? false
            log("blind-push: legacy registration \(ok ? "ok" : "failed") (\(why))")
            return ok ? .legacyRegistered : .failed
        }

        var caps = Caps.unknown
        if let url = URL(string: "\(pushBaseURL)/capabilities") {
            var r = URLRequest(url: url)
            r.httpMethod = "GET"
            r.timeoutInterval = 5
            if let res = await pushSend(r) { caps = parseCaps(status: res.status, body: res.body, pushBaseURL: pushBaseURL) }
        }
        var active = cachedActive
        if caps == .unsupported, active { active = false; setActive(false) }
        guard !tokens.isEmpty, shouldUseBlind(caps, cachedActive: active) else { return await legacy("caps") }

        let sealURL: URL? = {
            if case .supported(let u) = caps { return u }
            return resolveSealURL("/push-gateway/v1/seal", pushBaseURL: pushBaseURL)
        }()
        var handles: [Token.Kind: String]?
        if let sealURL, let body = sealRequestBody(tokens), let r = await post(sealURL, body, sealSend), (200..<300).contains(r.status) {
            handles = parseSealResponse(r.body, expected: tokens.map(\.kind))
        }
        guard let handles else {
            if active { log("blind-push: seal failed, retry later"); return .retryLater }
            return await legacy("seal failed")
        }
        guard let data = try? JSONSerialization.data(withJSONObject: blindPayload(from: legacyPayload, handles: handles)) else { return .failed }
        guard let reg = await post(registerURL, data, pushSend) else {
            if active { return .retryLater }
            return await legacy("register network error")
        }
        if (200..<300).contains(reg.status) && blindFlag(reg.body, "accepted") {
            setActive(true)
            log("blind-push: handles accepted")
            return .blindAccepted
        }
        if (200..<300).contains(reg.status) || !blindFlag(reg.body, "supported") {
            setActive(false)
            return await legacy("not accepted")
        }
        if active { log("blind-push: register refused \(reg.status), retry later"); return .retryLater }
        return await legacy("refused \(reg.status)")
    }
}
