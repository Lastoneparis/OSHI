//
//  CashuTokens.swift
//  OSHI
//
//  🪙 Cashu e-cash tokens pasted into a conversation: recognise, read, preview.
//
//  Swift half of `OSHI-Android/.../util/CashuTokens.kt` + `CashuCbor.kt`. The
//  two must agree, because the same token crosses between an iPhone and an
//  Android phone over the mesh and has to read the same on both.
//
//  ══ WHAT THIS IS, AND FIRMLY WHAT IT IS NOT ══
//
//  A Cashu token is a BEARER INSTRUMENT: the string IS the money. Whoever holds
//  it can spend it, like a banknote left on a table. That one fact decides the
//  design: OSHI reads a token and nothing else. No mint is contacted, no
//  balance is held, nothing is signed, swapped or melted, and OSHI never takes
//  custody of anyone's funds. Redeeming hands the token to a wallet the user
//  already trusts, through the `cashu:` URI NUT-00 defines.
//
//  The consequence is the point: there is no money inside OSHI to lose, to
//  regulate, or to seize — which is also why this ships without the legal
//  entity, Stripe Connect application and Apple organization account that
//  MASTERPLAN_MESH_PAYMENTS.md PART B put in front of every wallet phase.
//
//  This parses attacker-controlled message content on every bubble that
//  renders, so every path is bounds-checked, size-capped, and returns nil
//  rather than trapping.
//
//  Format: https://github.com/cashubtc/nuts/blob/main/00.md
//

import Foundation

// MARK: - Minimal CBOR

/// Just enough CBOR to read a V4 token, and not one byte more.
///
/// Hand-rolled for the same reason `MeshtasticManager` hand-rolls protobuf: a
/// 200-line reader that knows only the five major types NUT-00 uses, refuses
/// indefinite-length items and bounds every allocation is a smaller thing to be
/// sure of than a general implementation, and adds no dependency.
enum CashuCbor {

    indirect enum Value {
        case uint(UInt64)
        case bytes([UInt8])
        case text(String)
        case array([Value])
        case map([String: Value])
        case null
    }

    private static let maxDepth = 8

    /// Decode exactly one item. Trailing bytes are an error, not something to
    /// ignore: rendering the prefix of a truncated payload as if it were whole
    /// is how a doctored token gets a bubble.
    static func decode(_ bytes: [UInt8]) -> Value? {
        var offset = 0
        guard let value = readValue(bytes, &offset, 0) else { return nil }
        return offset == bytes.count ? value : nil
    }

    private static func readValue(_ b: [UInt8], _ i: inout Int, _ depth: Int) -> Value? {
        if depth > maxDepth { return nil }
        guard i < b.count else { return nil }
        let head = b[i]; i += 1
        let major = head >> 5
        let info = head & 0x1F
        switch major {
        case 0:
            guard let n = readLength(b, &i, info) else { return nil }
            return .uint(n)
        case 2:
            guard let n = readCount(b, &i, info), i + n <= b.count else { return nil }
            defer { i += n }
            return .bytes(Array(b[i..<(i + n)]))
        case 3:
            guard let n = readCount(b, &i, info), i + n <= b.count,
                  let s = String(bytes: b[i..<(i + n)], encoding: .utf8) else { return nil }
            i += n
            return .text(s)
        case 4:
            guard let n = readCount(b, &i, info) else { return nil }
            var items: [Value] = []
            items.reserveCapacity(min(n, 1024))
            for _ in 0..<n {
                guard let v = readValue(b, &i, depth + 1) else { return nil }
                items.append(v)
            }
            return .array(items)
        case 5:
            guard let n = readCount(b, &i, info) else { return nil }
            var map: [String: Value] = [:]
            for _ in 0..<n {
                guard let k = readValue(b, &i, depth + 1),
                      let v = readValue(b, &i, depth + 1) else { return nil }
                // NUT-00 tells receivers to ignore fields they do not know, so a
                // future non-text key must not break a token we can otherwise read.
                if case let .text(key) = k { map[key] = v }
            }
            return .map(map)
        case 6:
            guard let v = readValue(b, &i, depth + 1) else { return nil }
            return v
        case 7:
            switch info {
            case 20: return .uint(0)
            case 21: return .uint(1)
            case 22, 23: return .null
            default: return nil
            }
        default:
            return nil
        }
    }

    private static func readLength(_ b: [UInt8], _ i: inout Int, _ info: UInt8) -> UInt64? {
        switch info {
        case 0..<24: return UInt64(info)
        case 24: guard i < b.count else { return nil }; defer { i += 1 }; return UInt64(b[i])
        case 25: return readBE(b, &i, 2)
        case 26: return readBE(b, &i, 4)
        case 27: return readBE(b, &i, 8)
        default: return nil    // 31 is the indefinite form; reserved otherwise
        }
    }

    private static func readBE(_ b: [UInt8], _ i: inout Int, _ n: Int) -> UInt64? {
        guard i + n <= b.count else { return nil }
        var v: UInt64 = 0
        for k in 0..<n { v = (v << 8) | UInt64(b[i + k]) }
        i += n
        return v
    }

    /// A length used to size an allocation, bounded by the bytes actually left,
    /// so a header claiming four billion entries costs nothing.
    private static func readCount(_ b: [UInt8], _ i: inout Int, _ info: UInt8) -> Int? {
        guard let n = readLength(b, &i, info) else { return nil }
        let remaining = UInt64(b.count - i)
        guard n <= remaining else { return nil }
        return Int(n)
    }
}

// MARK: - Tokens

enum CashuTokens {

    struct Proof: Equatable {
        let amount: Int64
        let secret: String
        /// The unblinded signature `C`, lower-case hex.
        let signature: String
        /// Keyset id, lower-case hex.
        let keysetId: String
    }

    struct Entry: Equatable {
        let mint: String
        let proofs: [Proof]
    }

    struct Token: Equatable {
        let entries: [Entry]
        /// `sat`, `msat`, `usd`… absent in old V3 tokens, where `sat` was assumed.
        let unit: String?
        let memo: String?
        /// `A` for the deprecated V3 form, `B` for V4.
        let version: Character
        /// The exact text recognised, so the UI re-emits it byte for byte.
        let raw: String

        var totalAmount: Int64 { entries.reduce(0) { $0 + $1.proofs.reduce(0) { $0 + $1.amount } } }
        var proofCount: Int { entries.reduce(0) { $0 + $1.proofs.count } }
        var mints: [String] {
            var seen: [String] = []
            for e in entries where !seen.contains(e.mint) { seen.append(e.mint) }
            return seen
        }
        /// "10 sat", defaulting the unit per NUT-00.
        var displayAmount: String { "\(totalAmount) \(unit ?? "sat")" }
        /// Handing this to the system IS "redeem": the user's own wallet takes over.
        var redeemURI: String { "cashu:" + raw }
    }

    /// Real tokens are a few KB; far larger is someone spending our CPU for us.
    static let maxTokenLength = 60_000
    private static let minTokenLength = 12
    /// The order of every satoshi that will ever exist.
    private static let maxAmount: Int64 = 2_100_000_000_000_000

    /// Pull the bare `cashuA…`/`cashuB…` out of a `cashu:` / `cashu://` URI,
    /// percent-decoding whatever app it was copied from.
    ///
    /// Deliberately more permissive about the payload alphabet than the
    /// in-prose scanner below: this is input a user typed on purpose, where
    /// strictness only makes a real token fail invisibly.
    static func bareToken(from raw: String) -> String? {
        var token = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = token.lowercased()
        if lower.hasPrefix("cashu://") { token = String(token.dropFirst(8)) }
        else if lower.hasPrefix("cashu:") { token = String(token.dropFirst(6)) }
        if token.contains("%"), let decoded = token.removingPercentEncoding { token = decoded }
        guard token.count >= minTokenLength, token.count <= maxTokenLength else { return nil }
        guard token.hasPrefix("cashuA") || token.hasPrefix("cashuB") else { return nil }
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_+/=."))
        guard token.unicodeScalars.allSatisfy({ allowed.contains($0) }) else { return nil }
        return token
    }

    /// Decode one serialized token, or nil when it is not one.
    ///
    /// `strict` is what `/pay` uses before putting a token on the wire: it also
    /// refuses a token whose amount is zero or unreadable.
    static func decode(_ serialized: String, strict: Bool = false) -> Token? {
        guard let bare = bareToken(from: serialized) else { return nil }
        let version = bare[bare.index(bare.startIndex, offsetBy: 5)]
        let body = String(bare.dropFirst(6))
        guard let data = base64URLDecode(body) else { return nil }
        let token: Token?
        switch version {
        case "A": token = decodeV3(data, raw: bare)
        case "B": token = decodeV4([UInt8](data), raw: bare)
        default: token = nil
        }
        guard let t = token else { return nil }
        // An amount past every satoshi there will ever be is a display attack,
        // not a payment.
        guard t.totalAmount >= 0, t.totalAmount <= maxAmount else { return nil }
        if strict && t.totalAmount <= 0 { return nil }
        return t
    }

    /// Base64url, tolerating the standard alphabet some senders paste.
    private static func base64URLDecode(_ body: String) -> Data? {
        var s = body.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        s = s.trimmingCharacters(in: CharacterSet(charactersIn: "="))
        let pad = (4 - s.count % 4) % 4
        s += String(repeating: "=", count: pad)
        return Data(base64Encoded: s)
    }

    // MARK: V3 — base64url of JSON

    private static func decodeV3(_ data: Data, raw: String) -> Token? {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let list = root["token"] as? [[String: Any]] else { return nil }
        var entries: [Entry] = []
        for obj in list {
            guard var mint = obj["mint"] as? String else { continue }
            while mint.hasSuffix("/") { mint.removeLast() }
            let proofs: [Proof] = (obj["proofs"] as? [[String: Any]] ?? []).compactMap { p in
                guard let amount = (p["amount"] as? NSNumber)?.int64Value,
                      let secret = p["secret"] as? String,
                      let c = p["C"] as? String,
                      let id = p["id"] as? String else { return nil }
                return Proof(amount: amount, secret: secret,
                             signature: c.lowercased(), keysetId: id.lowercased())
            }
            if !proofs.isEmpty { entries.append(Entry(mint: mint, proofs: proofs)) }
        }
        guard !entries.isEmpty else { return nil }
        return Token(entries: entries,
                     unit: root["unit"] as? String,
                     memo: root["memo"] as? String,
                     version: "A",
                     raw: raw)
    }

    // MARK: V4 — base64url of CBOR

    private static func decodeV4(_ bytes: [UInt8], raw: String) -> Token? {
        guard case let .map(root)? = CashuCbor.decode(bytes),
              case let .text(mintRaw)? = root["m"],
              case let .array(groups)? = root["t"] else { return nil }
        var mint = mintRaw
        while mint.hasSuffix("/") { mint.removeLast() }

        var proofs: [Proof] = []
        for group in groups {
            guard case let .map(g) = group,
                  case let .bytes(idBytes)? = g["i"],
                  case let .array(list)? = g["p"] else { continue }
            let keysetId = hex(idBytes)
            for p in list {
                guard case let .map(po) = p,
                      case let .uint(amount)? = po["a"],
                      case let .text(secret)? = po["s"],
                      case let .bytes(c)? = po["c"],
                      amount <= UInt64(Int64.max) else { continue }
                proofs.append(Proof(amount: Int64(amount), secret: secret,
                                    signature: hex(c), keysetId: keysetId))
            }
        }
        guard !proofs.isEmpty else { return nil }
        var unit: String? = nil
        if case let .text(u)? = root["u"] { unit = u }
        var memo: String? = nil
        if case let .text(d)? = root["d"] { memo = d }
        // V4 carries one mint for the whole token, so there is exactly one entry.
        return Token(entries: [Entry(mint: mint, proofs: proofs)],
                     unit: unit, memo: memo, version: "B", raw: raw)
    }

    private static func hex(_ bytes: [UInt8]) -> String {
        let digits = Array("0123456789abcdef")
        var out = ""
        out.reserveCapacity(bytes.count * 2)
        for b in bytes {
            out.append(digits[Int(b >> 4)])
            out.append(digits[Int(b & 0x0F)])
        }
        return out
    }

    // MARK: Finding tokens in a message

    struct Match {
        let token: Token
        let range: Range<String.Index>
    }

    /// The in-prose scanner. Base64url only, plus the `.` of legacy multi-part
    /// tokens: a looser alphabet swallows sentences, and the match has to
    /// decode anyway before anything is reported.
    private static let pattern = try? NSRegularExpression(
        pattern: "(?:cashu://|cashu:)?cashu[AB][A-Za-z0-9_=.-]{20,}")

    /// Every token in `text`, skipping anything that does not decode — a bubble
    /// must never offer to redeem a sentence.
    static func find(in text: String) -> [Match] {
        guard text.count <= maxTokenLength * 2,
              let pattern else { return [] }
        let ns = text as NSString
        return pattern.matches(in: text, range: NSRange(location: 0, length: ns.length))
            .compactMap { m in
                guard let range = Range(m.range, in: text),
                      let token = decode(String(text[range])) else { return nil }
                return Match(token: token, range: range)
            }
    }

    static func containsToken(_ text: String) -> Bool { !find(in: text).isEmpty }
}
