//
//  LinkDetectionTests.swift
//  OSHITests
//
//  __GROUP_OPEN_HITCH_2026_11_22__ Link detection moved off the main thread
//  and behind a cache (LinkDetection). Three things can go wrong with that and
//  each has its own guard here:
//
//   1. a pre-check that says "no link possible" for a text that HAS one — the
//      link would silently stop being tappable. Guarded against a corpus and
//      a seeded random fuzz, using a real `NSDataDetector` as the oracle;
//   2. the composed bubble drifting from what the old synchronous code drew.
//      Guarded against a VERBATIM copy of the old `attributedText` and
//      `detectFirstURL()`;
//   3. a cache that serves the wrong entry (another text, another colour).
//
//  Plus the measurement: main-thread time to lay out 500 group bubbles, old
//  synchronous path vs. the new one, in a hosted view.
//

import XCTest
import SwiftUI
@testable import OSHI

// MARK: - The old code, verbatim (reference)

/// `ClickableTextView.attributedText` as of f55f65c2, minus the log line.
private func legacyAttributedText(text: String, textColor: Color, linkColor: Color) -> AttributedString {
    let (plain, spans) = InlineMarkup.parse(text)
    var attributedString = AttributedString(plain)
    attributedString.foregroundColor = textColor
    for span in spans {
        guard let r = Range(NSRange(location: span.location, length: span.length),
                            in: attributedString) else { continue }
        switch span.kind {
        case .bold: attributedString[r].inlinePresentationIntent = .stronglyEmphasized
        case .italic: attributedString[r].inlinePresentationIntent = .emphasized
        case .strikethrough: attributedString[r].strikethroughStyle = .single
        case .code: attributedString[r].inlinePresentationIntent = .code
        case .underline: attributedString[r].underlineStyle = .single
        }
    }
    for ticker in TickerLinks.matches(in: plain).reversed() {
        guard let r = Range(NSRange(ticker.range, in: plain), in: attributedString),
              let url = ticker.url else { continue }
        var chip = AttributedString(ticker.display)
        chip.link = url
        chip.foregroundColor = linkColor
        chip.inlinePresentationIntent = .stronglyEmphasized
        attributedString.replaceSubrange(r, with: chip)
    }
    let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
    let nsRange = NSRange(plain.startIndex..., in: plain)
    let matches = detector?.matches(in: plain, options: [], range: nsRange)
    matches?.forEach { match in
        guard let range = Range(match.range, in: plain) else { return }
        let urlString = String(plain[range])
        var url: URL?
        if let directURL = URL(string: urlString), directURL.scheme != nil {
            url = directURL
        } else if urlString.hasPrefix("www.") {
            url = URL(string: "https://\(urlString)")
        } else if !urlString.contains("://") {
            url = URL(string: "https://\(urlString)")
        }
        if let finalURL = url, let attrRange = Range(match.range, in: attributedString) {
            attributedString[attrRange].foregroundColor = linkColor
            attributedString[attrRange].underlineStyle = .single
            attributedString[attrRange].link = finalURL
        }
    }
    if let addressDetector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.address.rawValue) {
        for match in addressDetector.matches(in: plain, options: [], range: nsRange) {
            guard let range = Range(match.range, in: plain),
                  let attrRange = Range(match.range, in: attributedString),
                  !attributedString[attrRange].runs.contains(where: { $0.link != nil }) else { continue }
            var comps = URLComponents()
            comps.scheme = MapOpener.addressScheme
            comps.host = "open"
            comps.queryItems = [URLQueryItem(name: "q", value: String(plain[range]))]
            guard let url = comps.url else { continue }
            attributedString[attrRange].foregroundColor = linkColor
            attributedString[attrRange].underlineStyle = .single
            attributedString[attrRange].link = url
        }
    }
    return attributedString
}

/// `String.detectFirstURL()` as of f55f65c2.
private func legacyFirstURL(_ s: String) -> String? {
    let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
    let matches = detector?.matches(in: s, options: [], range: NSRange(location: 0, length: s.utf16.count))
    if let match = matches?.first, let range = Range(match.range, in: s) {
        let urlString = String(s[range])
        if urlString.lowercased().hasPrefix("http://") || urlString.lowercased().hasPrefix("https://") {
            return urlString
        } else if urlString.lowercased().hasPrefix("www.") {
            return "https://\(urlString)"
        } else if !urlString.contains("://") {
            return "https://\(urlString)"
        }
        return urlString
    }
    return nil
}

/// The OLD bubble, for the before/after measurement: the old body was exactly
/// `Text(attributedText).fontWeight(weight)` with the scan inside `body`.
private struct LegacyBubble: View {
    let text: String
    var body: some View {
        Text(legacyAttributedText(text: text, textColor: .primary, linkColor: .blue))
            .fontWeight(.regular)
    }
}

// MARK: - Corpus

enum LinkCorpus {
    /// Things that ARE links or addresses for `NSDataDetector`, or are close
    /// enough that a pre-check could plausibly get them wrong.
    static let linky: [String] = [
        "https://oshi-messenger.com", "http://example.com/a?b=c#d", "HTTP://A.COM", "WWW.A.COM",
        "www.styia.com", "github.com", "x.com", "a.io", "maps.apple.com/?q=x",
        "https://oshi-messenger.com/g/paris-tech", "oshi-messenger.com/g/abc_123",
        "https://oshi-messenger.com/add?key=KEY", "oshi://group/abc", "oshi://g/abc",
        "oshi://add?key=abc", "oshi://chat/xyz", "oshi://web-auth?c=1",
        "hugo@oshi-messenger.com", "mailto:hugo@oshi-messenger.com", "a.b@c.de",
        "tel:0612345678", "tel:+33612345678", "sms:123", "skype:foo", "news:comp.lang",
        "file:///x", "ssh://h", "ftp://files.example.org/x.zip", "https://a",
        "http://[::1]/", "http://192.168.1.1:8080/admin", "192.168.1.1", "10.0.0.1:22",
        "1.2.3.4", "2001:db8::1", "[2001:db8::1]:443",
        "abcdefghijklmnop.onion", "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/",
        "xn--bcher-kva.ch", "bücher.de", "пример.рф", "例子.中国", "例子。中国", "example．com",
        "+33 6 12 34 56 78", "06 12 34 56 78", "(415) 555-0100", "+1-202-555-0143",
        "1 Infinite Loop, Cupertino, CA 95014", "10 rue de Rivoli 75001 Paris",
        "Rue de Rivoli, Paris, France", "Apple Park Way, Cupertino, CA",
        "Avenue des Champs Elysees\nParis", "calle mayor madrid", "Piazza Navona Roma",
        "Fifth Avenue New York NY", "东京都千代田区千代田1-1", "東京都千代田区丸の内1丁目",
        "北京市朝阳区建国路88号", "서울특별시 중구 세종대로 110", "Москва, ул. Тверская, 7",
        "@AAPL", "@NASDAQ:TSLA", "@gold", "magnet:?xt=urn:btih:abc", "bitcoin:bc1qxyz",
        "geo:48.85,2.35", "www.x", "a.b.c", "Hello.World", "ok.Ce soir", "end.)",
    ]

    /// Ordinary chat lines.
    static let chat: [String] = [
        "ok", "merci !", "lol", "Bonjour. Ça va ?", "Note: demain", "On se voit ce soir ?",
        "*gras* et _italique_", "~barré~ `code`", "👍", "😂😂😂", "Salut", "d'accord",
        "je suis en route", "tu as vu le match hier", "Rendez-vous à 18h30 devant la gare",
        "Ich komme morgen", "¿Dónde estás?", "مرحبا", "привет как дела", "こんにちは",
        "สวัสดีครับ", "안녕하세요", "a", "", " ", "\n", "...", "?!", "100%",
    ]

    /// Every entry alone, and embedded in a sentence with formatting around it.
    static var all: [String] {
        var out = linky + chat
        for s in linky {
            out.append("Regarde ça : \(s) et dis-moi.")
            out.append("*\(s)*")
            out.append("_voir_ \(s), puis @AAPL")
        }
        return out
    }

    private static func found(_ s: String, _ type: NSTextCheckingResult.CheckingType) -> Bool {
        let d = try! NSDataDetector(types: type.rawValue)
        return !d.matches(in: s, options: [], range: NSRange(s.startIndex..., in: s)).isEmpty
    }
    static func hasLink(_ s: String) -> Bool { found(s, .link) }
    static func hasAddress(_ s: String) -> Bool { found(s, .address) }
}

// MARK: - Pre-check

final class LinkDetectionPreCheckTests: XCTestCase {

    /// The one property that matters: whenever the detector WOULD find
    /// something, the pre-check must let it run.
    func testPreCheckHasNoFalseNegativesOnTheCorpus() {
        var linksSeen = 0, addressesSeen = 0
        for s in LinkCorpus.all {
            let plain = InlineMarkup.parse(s).0
            if LinkCorpus.hasLink(plain) {
                linksSeen += 1
                XCTAssertTrue(LinkDetection.mightContainLink(plain), "link pre-check skipped \(plain.debugDescription)")
            }
            if LinkCorpus.hasAddress(plain) {
                addressesSeen += 1
                XCTAssertTrue(LinkDetection.mightContainAddress(plain), "address pre-check skipped \(plain.debugDescription)")
            }
            if LinkCorpus.hasLink(s) {
                XCTAssertTrue(LinkDetection.mightContainLink(s), "first-URL pre-check skipped \(s.debugDescription)")
            }
        }
        // The corpus must actually exercise the detectors, or this proves nothing.
        XCTAssertGreaterThan(linksSeen, 40)
        XCTAssertGreaterThan(addressesSeen, 5)
    }

    /// Seeded fuzz over an alphabet rich in the characters the rules look at.
    func testPreCheckHasNoFalseNegativesOnRandomText() {
        var rng = SplitMix64(seed: 0x05_11_22)
        let pieces = ["a", "b", "com", "www", "fr", "io", "rue", "Paris", "de", "calle", "mayor", "madrid",
                      ".", "。", "．", ":", "/", "//", "@", "-", "_", "?", "=", "#", ",", " ", "\n",
                      "1", "42", "75001", "+33", "http", "https", "tel", "oshi", "onion", "丁目", "東京",
                      "*", "~", "`", "é", "ü", "рф", "пример"]
        var linksSeen = 0, addressesSeen = 0
        for _ in 0..<3_000 {
            func noise(_ k: Int) -> String {
                (0..<k).map { _ in pieces[Int(rng.next() % UInt64(pieces.count))] }.joined()
            }
            func pick(_ a: [String]) -> String { a[Int(rng.next() % UInt64(a.count))] }
            let s: String
            switch rng.next() % 3 {
            case 0:  // free noise
                s = noise(Int(rng.next() % 9) + 1)
            case 1:  // link-shaped: [noise][scheme][label][dot][label][noise]
                s = noise(Int(rng.next() % 3)) + pick(["", "https://", "www.", "tel:", "oshi://", "mailto:", "a@"])
                    + pick(["a", "oshi", "x", "пример", "東京", "1"]) + pick([".", "。", "．", ":", ". ", ""])
                    + pick(["com", "io", "рф", "onion", "fr", "1", "g/abc"]) + noise(Int(rng.next() % 3))
            default: // address-shaped
                s = pick(["", "1 ", "10 ", "42, "]) + pick(["rue", "calle", "Avenue", "Piazza"]) + " "
                    + pick(["de Rivoli", "mayor", "Navona", "des Champs"]) + pick([" ", ", ", "\n"])
                    + pick(["Paris", "madrid", "Roma", "75001 Paris"]) + noise(Int(rng.next() % 2))
            }
            if LinkCorpus.hasLink(s) {
                linksSeen += 1
                XCTAssertTrue(LinkDetection.mightContainLink(s), "link pre-check skipped \(s.debugDescription)")
            }
            if LinkCorpus.hasAddress(s) {
                addressesSeen += 1
                XCTAssertTrue(LinkDetection.mightContainAddress(s), "address pre-check skipped \(s.debugDescription)")
            }
        }
        XCTAssertGreaterThan(linksSeen, 300)
        XCTAssertGreaterThan(addressesSeen, 100)
        print("⏱ fuzz: \(linksSeen) link texts, \(addressesSeen) address texts")
    }

    /// And it must actually skip something, or it is dead weight.
    func testPreCheckSkipsOrdinaryShortChat() {
        for s in ["ok", "merci!", "lol", "Salut", "👍", "😂😂😂", "d'accord", "?!", ""] {
            XCTAssertFalse(LinkDetection.needsScan(s), "\(s.debugDescription) should not need a scan")
        }
        XCTAssertFalse(LinkDetection.mightContainLink("Bonjour. Ça va ? Note: demain"))
    }
}

// MARK: - Equivalence with the old synchronous result

final class LinkDetectionEquivalenceTests: XCTestCase {

    override func setUp() { LinkDetection.resetCache() }

    func testComposedBubbleMatchesTheOldSynchronousOne() {
        let palettes: [(Color, Color)] = [(.primary, .blue), (.white, .cyan),
                                          (Color(.label), BubbleStyleManager.sentAccentInk)]
        var withLinks = 0
        for s in LinkCorpus.all {
            for (ink, link) in palettes {
                let old = legacyAttributedText(text: s, textColor: ink, linkColor: link)
                let result = LinkDetection.scan(plain: InlineMarkup.parse(s).0)
                let new = LinkDetection.compose(text: s, textColor: ink, linkColor: link, detected: result)
                XCTAssertEqual(new, old, "bubble differs from the old one for \(s.debugDescription)")
                // Second call is served from the styled cache — must still match.
                let again = LinkDetection.compose(text: s, textColor: ink, linkColor: link, detected: result)
                XCTAssertEqual(again, old)
                if !result.isEmpty { withLinks += 1 }
            }
        }
        XCTAssertGreaterThan(withLinks, 100)
    }

    /// The provisional string (before detection lands) differs from the final
    /// one ONLY by link attributes — same characters, same formatting — so
    /// the bubble cannot change size when links arrive.
    func testProvisionalStringHasTheFinalCharactersAndFormatting() {
        for s in LinkCorpus.all {
            let final = legacyAttributedText(text: s, textColor: .primary, linkColor: .blue)
            let early = LinkDetection.compose(text: s, textColor: .primary, linkColor: .blue, detected: nil)
            XCTAssertEqual(String(early.characters), String(final.characters), s)
            // Formatting intents (bold/italic/code) — the only attributes
            // here that change metrics — must be identical character by character.
            let earlyIntents = early.characters.indices.map { early[$0..<early.characters.index(after: $0)].runs.first?.inlinePresentationIntent }
            let finalIntents = final.characters.indices.map { final[$0..<final.characters.index(after: $0)].runs.first?.inlinePresentationIntent }
            XCTAssertEqual(earlyIntents, finalIntents, "formatting differs between provisional and final for \(s.debugDescription)")
        }
    }

    func testFirstURLMatchesTheOldOne() {
        for s in LinkCorpus.all {
            XCTAssertEqual(LinkDetection.firstURL(in: s), legacyFirstURL(s), s)
            XCTAssertEqual(s.detectFirstURL(), legacyFirstURL(s), s)   // cached path
        }
    }

    func testAsyncResolveMatchesTheSynchronousScan() async {
        for s in LinkCorpus.linky {
            let a = await LinkDetection.resolve(text: s)
            XCTAssertEqual(a, LinkDetection.scan(plain: InlineMarkup.parse(s).0), s)
            let u = await LinkDetection.firstURLAsync(in: s)
            XCTAssertEqual(u, legacyFirstURL(s), s)
        }
    }
}

// MARK: - Cache key and invalidation

final class LinkDetectionCacheTests: XCTestCase {

    override func setUp() { LinkDetection.resetCache() }

    func testUnknownTextIsNotCachedUntilResolved() async {
        let s = "voir https://oshi-messenger.com/g/abc demain"
        XCTAssertNil(LinkDetection.cached(text: s), "a linky text must not be reported as known before a scan")
        let r = await LinkDetection.resolve(text: s)
        XCTAssertEqual(r.links.count, 1)
        XCTAssertEqual(LinkDetection.cached(text: s), r)
        // Another text — even a one-character edit — is a different key.
        XCTAssertNil(LinkDetection.cached(text: s + "!"))
        XCTAssertNil(LinkDetection.cached(text: "voir https://oshi-messenger.com/g/abd demain"))
    }

    func testTextThatCannotHoldALinkIsKnownImmediately() {
        XCTAssertEqual(LinkDetection.cached(text: "merci"), LinkDetection.Result.empty)
        XCTAssertEqual(LinkDetection.cached(text: "*ok*"), LinkDetection.Result.empty)
    }

    func testMarkersAreKeyedOnTheRawText() async {
        // Same visible words, different markers → different attributed result,
        // so they must not share a styled entry.
        let a = LinkDetection.compose(text: "*x.com*", textColor: .primary, linkColor: .blue,
                                      detected: await LinkDetection.resolve(text: "*x.com*"))
        let b = LinkDetection.compose(text: "x.com", textColor: .primary, linkColor: .blue,
                                      detected: await LinkDetection.resolve(text: "x.com"))
        XCTAssertEqual(String(a.characters), String(b.characters))
        XCTAssertNotEqual(a, b)
    }

    /// A theme / bubble-style change gives a new link colour; the bubble must
    /// draw in it, not in the colour of whoever filled the cache first.
    func testColourChangeIsNotServedAStaleEntry() async {
        let s = "https://oshi-messenger.com"
        let r = await LinkDetection.resolve(text: s)
        let blue = LinkDetection.compose(text: s, textColor: .primary, linkColor: .blue, detected: r)
        let red = LinkDetection.compose(text: s, textColor: .primary, linkColor: .red, detected: r)
        XCTAssertEqual(blue.runs.first?.foregroundColor, .blue)
        XCTAssertEqual(red.runs.first?.foregroundColor, .red)
        let white = LinkDetection.compose(text: s, textColor: .white, linkColor: .red, detected: r)
        XCTAssertEqual(white, legacyAttributedText(text: s, textColor: .white, linkColor: .red))
    }

    /// The provisional string is never cached as if it were final.
    func testProvisionalStringIsNotCachedAsFinal() async {
        let s = "go to github.com"
        _ = LinkDetection.compose(text: s, textColor: .primary, linkColor: .blue, detected: nil)
        let r = await LinkDetection.resolve(text: s)
        let final = LinkDetection.compose(text: s, textColor: .primary, linkColor: .blue, detected: r)
        XCTAssertTrue(final.runs.contains { $0.link != nil })
    }

    func testConcurrentResolvesShareOneAnswer() async {
        let s = "rdv 10 rue de Rivoli 75001 Paris ou https://x.com"
        let results = await withTaskGroup(of: LinkDetection.Result.self) { g in
            for _ in 0..<20 { g.addTask { await LinkDetection.resolve(text: s) } }
            var out: [LinkDetection.Result] = []
            for await r in g { out.append(r) }
            return out
        }
        XCTAssertEqual(results.count, 20)
        XCTAssertEqual(Set(results.map { $0.links.count }), [1])
        XCTAssertTrue(results.allSatisfy { $0 == results[0] })
    }

    /// The view's identity for `.equatable()` is its four inputs, not its
    /// late-arriving state.
    func testViewEqualityIgnoresDetectionState() {
        let a = ClickableTextView(text: "x.com", textColor: .black, linkColor: .blue)
        XCTAssertTrue(a == ClickableTextView(text: "x.com", textColor: .black, linkColor: .blue))
        XCTAssertFalse(a == ClickableTextView(text: "x.com", textColor: .black, linkColor: .red))
        XCTAssertFalse(a == ClickableTextView(text: "y.com", textColor: .black, linkColor: .blue))
    }
}

// MARK: - Main-thread cost of opening a 500-message group

@MainActor
final class GroupOpenLinkDetectionCostTests: XCTestCase {

    /// 500 group-like messages: mostly chat, some links, a few addresses and
    /// tickers — and all distinct, so no cache is warm by accident.
    private func messages() -> [String] {
        let base = LinkCorpus.chat.filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            + LinkCorpus.linky.map { "regarde \($0) stp" }
            + ["On se retrouve 10 rue de Rivoli 75001 Paris à 19h ?",
               "Le lien du groupe : https://oshi-messenger.com/g/paris-tech, partage-le.",
               "J'ai acheté du @AAPL et du @gold, *énorme* journée"]
        return (0..<500).map { i in "\(base[i % base.count]) #\(i) — message \(i) du fil de groupe." }
    }

    private func layoutCost<V: View>(_ label: String, _ make: @escaping (String) -> V) -> Double {
        let texts = messages()
        let root = ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(texts.enumerated()), id: \.offset) { _, t in make(t) }
            }
        }
        let host = UIHostingController(rootView: root)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 430, height: 932))
        window.rootViewController = host
        let t0 = CFAbsoluteTimeGetCurrent()
        window.makeKeyAndVisible()
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        let dt = (CFAbsoluteTimeGetCurrent() - t0) * 1000
        window.isHidden = true
        print("⏱ \(label): \(String(format: "%.1f", dt)) ms main thread for 500 bubbles")
        return dt
    }

    func testOpeningA500MessageGroupNoLongerScansOnTheMainThread() {
        // Warm-up so neither side pays SwiftUI's / the detector frameworks'
        // one-time costs inside the measurement.
        _ = layoutCost("warm-up (legacy)") { LegacyBubble(text: "warm " + $0) }
        LinkDetection.resetCache()
        _ = layoutCost("warm-up (new)") { ClickableTextView(text: "warm " + $0).equatable() }

        LinkDetection.resetCache()
        let before = layoutCost("BEFORE (sync NSDataDetector in body)") { LegacyBubble(text: $0) }
        LinkDetection.resetCache()
        let after = layoutCost("AFTER (cold cache, detection off-main)") { ClickableTextView(text: $0).equatable() }
        let reopen = layoutCost("AFTER (warm cache, re-open)") { ClickableTextView(text: $0).equatable() }

        XCTAssertLessThan(after, before * 0.6,
                          "the first open still pays for link detection on the main thread (before \(before) ms, after \(after) ms)")
        XCTAssertLessThan(reopen, before * 0.6)
    }
}

/// Deterministic PRNG for the fuzz test.
private struct SplitMix64 {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
}
