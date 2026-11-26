//
//  LinkDetection.swift
//  OSHI
//
//  __GROUP_OPEN_HITCH_2026_11_22__ Link and street-address detection for
//  message bubbles, moved OFF the main thread and cached.
//
//  MEASURED (Animation Hitches + Time Profiler, iPhone 17 Pro Max, build
//  f55f65c2): opening a big group stalled the main thread for 598 ms, with a
//  350 ms dropped frame. ~180 ms of it was `ClickableTextView.attributedText`
//  — two `NSDataDetector` scans (link, then address) run synchronously inside
//  `body` for every bubble materialised on open, each with a freshly
//  allocated detector (allocation alone: ~16 ms of the trace) — and another
//  ~27 ms was `String.detectFirstURL()` in each group bubble's `onAppear`.
//
//  The shape of the fix:
//
//   * What is expensive — the two detector passes — depends on the message
//     text ONLY. So it is computed once per text and kept in a bounded
//     `NSCache` (`Result`), whatever colours the bubble is later drawn in. A
//     theme or bubble-style change therefore never re-scans anything.
//   * It is computed on a background queue, with one detector of each kind per
//     thread (detectors are expensive to build; a thread-local copy needs no
//     lock).
//   * Until the result exists the bubble draws the SAME text with the SAME
//     formatting and font — only the link attributes (colour, underline,
//     `.link`) are missing. None of them changes a glyph's metrics, so the
//     bubble's size does not move when they arrive.
//   * Texts that cannot contain a link or an address skip detection entirely
//     (`mightContainLink` / `mightContainAddress`). These pre-checks are
//     deliberately loose: a false positive costs one background scan, a false
//     negative would be a missing link — so every rule below was derived from
//     what `NSDataDetector` was observed to match, never from what a link
//     "usually" looks like (it matches "calle mayor madrid" as an address).
//
//  Building the attributed string (`compose`) replays the detector hits
//  through EXACTLY the old `ClickableTextView.attributedText` steps, in the
//  same order, against the same strings — including applying the hits' ranges
//  (computed on the marker-free text) to the post-ticker attributed string.
//  `LinkDetectionTests` pins that equivalence against a verbatim copy of the
//  old synchronous code.
//

import SwiftUI

enum LinkDetection {

    // MARK: - Result

    /// One detector hit, already turned into the URL the bubble will open.
    /// `range` is in the coordinates of the marker-free text
    /// (`InlineMarkup.parse(text).0`), as the old code computed it.
    struct Hit: Equatable, Sendable {
        let range: NSRange
        let url: URL
    }

    /// Everything the detectors found in one message text.
    struct Result: Equatable, Sendable {
        let links: [Hit]
        let addresses: [Hit]
        static let empty = Result(links: [], addresses: [])
        var isEmpty: Bool { links.isEmpty && addresses.isEmpty }
    }

    // MARK: - Pre-checks

    /// Dot-like separators `NSDataDetector` might accept between host labels.
    private static let dotLike: Set<Unicode.Scalar> = [".", "\u{3002}", "\u{FF0E}", "\u{FF61}"]

    /// False only when the link detector CANNOT match anything in `s`.
    ///
    /// Every link it produces needs either a scheme (`https:`, `tel:`,
    /// `mailto:`, `oshi://`, `[::1]`…) — a `:` with something right after it —
    /// or a host with a label after a dot (`x.com`, `hugo@a.io`, `пример.рф`,
    /// `abc.onion`). A `.` or `:` followed by whitespace or the end of the
    /// text ("Bonjour. Ça va ?", "Note: demain") cannot start either. `@` is
    /// included only to stay loose: every e-mail it matches also has a dot.
    static func mightContainLink(_ s: String) -> Bool {
        var armed = false
        for scalar in s.unicodeScalars {
            if armed, !scalar.properties.isWhitespace { return true }
            armed = scalar == ":" || scalar == "@" || dotLike.contains(scalar)
        }
        return false
    }

    /// False only when the address detector CANNOT match anything in `s`.
    ///
    /// Observed: it matches addresses with no digit at all ("calle mayor
    /// madrid", "Rue de Rivoli, Paris, France"), so digits alone are not a
    /// usable test. What it never matched is a single Latin word. So: any
    /// digit or other numeric character, OR two tokens separated by
    /// whitespace / a comma, OR any letter from a script written without
    /// spaces (U+0E00 and above: Thai, CJK, Hangul… where an address can be
    /// one unbroken run) is enough to run the scan.
    static func mightContainAddress(_ s: String) -> Bool {
        var sawToken = false
        var sawSeparatorAfterToken = false
        for scalar in s.unicodeScalars {
            let p = scalar.properties
            if p.numericType != nil { return true }
            if scalar.value >= 0x0E00, p.isAlphabetic { return true }
            if p.isWhitespace || scalar == "," || scalar == "\u{FF0C}" || scalar == "\u{3001}" {
                if sawToken { sawSeparatorAfterToken = true }
            } else {
                if sawSeparatorAfterToken { return true }
                sawToken = true
            }
        }
        return false
    }

    /// True when `plain` needs a detector pass at all.
    static func needsScan(_ plain: String) -> Bool {
        mightContainLink(plain) || mightContainAddress(plain)
    }

    // MARK: - Detectors (one per thread)

    private static let linkKey = "oshi.LinkDetection.link"
    private static let addressKey = "oshi.LinkDetection.address"

    private static func detector(_ key: String, _ type: NSTextCheckingResult.CheckingType) -> NSDataDetector? {
        let dict = Thread.current.threadDictionary
        if let d = dict[key] as? NSDataDetector { return d }
        guard let d = try? NSDataDetector(types: type.rawValue) else { return nil }
        dict[key] = d
        return d
    }

    // MARK: - Scanning (any thread)

    /// The detector pass the old `attributedText` ran inline, on the
    /// marker-free text. Pure and thread-safe; normally called off-main.
    static func scan(plain: String) -> Result {
        guard needsScan(plain) else { return .empty }
        let nsRange = NSRange(plain.startIndex..., in: plain)

        var links: [Hit] = []
        if mightContainLink(plain), let detector = detector(linkKey, .link) {
            for match in detector.matches(in: plain, options: [], range: nsRange) {
                guard let range = Range(match.range, in: plain) else { continue }
                let urlString = String(plain[range])
                var url: URL?
                if let directURL = URL(string: urlString), directURL.scheme != nil {
                    url = directURL
                } else if urlString.hasPrefix("www.") {
                    url = URL(string: "https://\(urlString)")
                } else if !urlString.contains("://") {
                    url = URL(string: "https://\(urlString)")
                }
                if let url { links.append(Hit(range: match.range, url: url)) }
            }
        }

        var addresses: [Hit] = []
        if mightContainAddress(plain), let detector = detector(addressKey, .address) {
            for match in detector.matches(in: plain, options: [], range: nsRange) {
                guard let range = Range(match.range, in: plain) else { continue }
                var comps = URLComponents()
                comps.scheme = MapOpener.addressScheme
                comps.host = "open"
                comps.queryItems = [URLQueryItem(name: "q", value: String(plain[range]))]
                guard let url = comps.url else { continue }
                addresses.append(Hit(range: match.range, url: url))
            }
        }
        return Result(links: links, addresses: addresses)
    }

    // MARK: - Cache

    private final class TextKey: NSObject {
        let text: String
        init(_ text: String) { self.text = text }
        override var hash: Int { text.hashValue }
        override func isEqual(_ object: Any?) -> Bool { (object as? TextKey)?.text == text }
    }

    private final class Box: NSObject {
        let result: Result
        init(_ result: Result) { self.result = result }
    }

    /// Detector results by message text. Bounded by count and by total text
    /// length (the cost), so a thread of huge messages cannot pin memory.
    private static let results: NSCache<TextKey, Box> = {
        let c = NSCache<TextKey, Box>()
        c.countLimit = 4_000
        c.totalCostLimit = 8_000_000   // UTF-16 units of text
        return c
    }()

    /// Cached detector result for `text` (the RAW message, markers included),
    /// or `.empty` at once when the text cannot hold a link or an address.
    /// `nil` means "not known yet — call `resolve`".
    static func cached(text: String, plain: String? = nil) -> Result? {
        if let hit = results.object(forKey: TextKey(text)) { return hit.result }
        let plain = plain ?? InlineMarkup.parse(text).0
        if !needsScan(plain) {
            store(.empty, for: text)
            return Result.empty
        }
        return nil
    }

    static func store(_ result: Result, for text: String) {
        results.setObject(Box(result), forKey: TextKey(text), cost: text.utf16.count)
    }

    /// Tests only.
    static func resetCache() {
        results.removeAllObjects()
        attributed.removeAllObjects()
        firstURLs.removeAllObjects()
    }

    // MARK: - Background resolution

    /// Two workers: enough that a long message does not hold up the short
    /// ones behind it, few enough that opening a 500-message group does not
    /// spawn a thread per bubble.
    private static let queue: OperationQueue = {
        let q = OperationQueue()
        q.name = "oshi.link-detection"
        q.maxConcurrentOperationCount = 2
        q.qualityOfService = .userInitiated
        return q
    }()

    private static let lock = NSLock()
    private static var waiting: [String: [CheckedContinuation<Result, Never>]] = [:]

    /// The detector result for `text`, scanned on the background queue if it
    /// is not cached yet. Concurrent requests for the same text share one scan.
    static func resolve(text: String) async -> Result {
        if let known = cached(text: text) { return known }
        return await withCheckedContinuation { (cont: CheckedContinuation<Result, Never>) in
            lock.lock()
            let alreadyRunning = waiting[text] != nil
            waiting[text, default: []].append(cont)
            lock.unlock()
            guard !alreadyRunning else { return }
            queue.addOperation {
                let result = scan(plain: InlineMarkup.parse(text).0)
                store(result, for: text)
                lock.lock()
                let conts = waiting.removeValue(forKey: text) ?? []
                lock.unlock()
                for c in conts { c.resume(returning: result) }
            }
        }
    }

    // MARK: - Composition (cheap, main thread)

    private final class StyledKey: NSObject {
        let text: String
        let textColor: Color
        let linkColor: Color
        init(_ text: String, _ textColor: Color, _ linkColor: Color) {
            self.text = text; self.textColor = textColor; self.linkColor = linkColor
        }
        override var hash: Int {
            var h = Hasher()
            h.combine(text); h.combine(textColor); h.combine(linkColor)
            return h.finalize()
        }
        override func isEqual(_ object: Any?) -> Bool {
            guard let o = object as? StyledKey else { return false }
            return o.text == text && o.textColor == textColor && o.linkColor == linkColor
        }
    }

    private final class StyledBox: NSObject {
        let value: AttributedString
        init(_ value: AttributedString) { self.value = value }
    }

    /// Finished bubbles by (text, text colour, link colour). Only FINAL
    /// strings (detection applied) go in. Fonts are not part of the key: the
    /// attributed string carries no font — size and weight come from the
    /// view's modifiers — so a font-size change reuses the same entry.
    private static let attributed: NSCache<StyledKey, StyledBox> = {
        let c = NSCache<StyledKey, StyledBox>()
        c.countLimit = 1_500
        c.totalCostLimit = 4_000_000
        return c
    }()

    /// The bubble's attributed string. With `detected == nil` it is the
    /// provisional one: formatting and tickers, no link attributes.
    static func compose(text: String, textColor: Color, linkColor: Color,
                        detected: Result?) -> AttributedString {
        let key = StyledKey(text, textColor, linkColor)
        if detected != nil, let hit = attributed.object(forKey: key) { return hit.value }

        // Inline formatting is stripped from the string BEFORE URL detection, so
        // ranges are computed against the text the user actually sees. Doing it
        // the other way round shifts every link by the number of markers removed.
        let (plain, spans) = InlineMarkup.parse(text)
        var attributedString = AttributedString(plain)
        attributedString.foregroundColor = textColor

        // Apply **bold** / *bold*, __underline__, _italic_, ~strike~ and `code`.
        // __TEXT_FORMATTING_2026_11_24__ Intents are MERGED with what a run
        // already carries, so `**_x_**` is bold AND italic instead of whichever
        // span happened to be applied last.
        func addIntent(_ intent: InlinePresentationIntent, _ r: Range<AttributedString.Index>) {
            let runs = attributedString[r].runs.map { ($0.range, $0.inlinePresentationIntent) }
            for (runRange, current) in runs {
                attributedString[runRange].inlinePresentationIntent = (current ?? []).union(intent)
            }
        }
        for span in spans {
            guard let r = Range(NSRange(location: span.location, length: span.length),
                                in: attributedString) else { continue }
            switch span.kind {
            case .bold:          addIntent(.stronglyEmphasized, r)
            case .italic:        addIntent(.emphasized, r)
            case .strikethrough: attributedString[r].strikethroughStyle = .single
            case .code:          addIntent(.code, r)
            case .underline:     attributedString[r].underlineStyle = .single
            }
        }

        // Stock tickers: `@AAPL` → a tappable `$AAPL` pointing at TradingView.
        // Walked in REVERSE so each replacement keeps earlier ranges valid.
        // See TickerLinks — the sigil is required.
        for ticker in TickerLinks.matches(in: plain).reversed() {
            guard let r = Range(NSRange(ticker.range, in: plain), in: attributedString),
                  let url = ticker.url else { continue }
            var chip = AttributedString(ticker.display)
            chip.link = url
            chip.foregroundColor = linkColor
            chip.inlinePresentationIntent = .stronglyEmphasized
            attributedString.replaceSubrange(r, with: chip)
        }

        guard let detected else { return attributedString }

        // URLs — ranges from `plain`, applied exactly as before.
        for hit in detected.links {
            guard let attrRange = Range(hit.range, in: attributedString) else { continue }
            attributedString[attrRange].foregroundColor = linkColor
            attributedString[attrRange].underlineStyle = .single
            attributedString[attrRange].link = hit.url
        }

        // Street addresses → a tappable link that MapOpener opens with the
        // app chosen in Settings › Maps. Skipped where a URL already sits.
        for hit in detected.addresses {
            guard let attrRange = Range(hit.range, in: attributedString),
                  !attributedString[attrRange].runs.contains(where: { $0.link != nil }) else { continue }
            attributedString[attrRange].foregroundColor = linkColor
            attributedString[attrRange].underlineStyle = .single
            attributedString[attrRange].link = hit.url
        }

        attributed.setObject(StyledBox(attributedString), forKey: key, cost: text.utf16.count)
        return attributedString
    }

    // MARK: - First URL (link previews)

    private final class URLBox: NSObject {
        let value: String?
        init(_ value: String?) { self.value = value }
    }
    private static let firstURLs: NSCache<TextKey, URLBox> = {
        let c = NSCache<TextKey, URLBox>()
        c.countLimit = 4_000
        c.totalCostLimit = 8_000_000
        return c
    }()

    /// `String.detectFirstURL()`'s answer, unchanged: the FIRST link match in
    /// the raw string, normalised the same way. Cached per text, pre-checked,
    /// and with a reused per-thread detector. Safe on any thread.
    static func firstURL(in text: String) -> String? {
        if let hit = firstURLs.object(forKey: TextKey(text)) { return hit.value }
        var answer: String?
        if mightContainLink(text), let detector = detector(linkKey, .link),
           let match = detector.firstMatch(in: text, options: [],
                                           range: NSRange(location: 0, length: text.utf16.count)),
           let range = Range(match.range, in: text) {
            let urlString = String(text[range])
            if urlString.lowercased().hasPrefix("http://") || urlString.lowercased().hasPrefix("https://") {
                answer = urlString
            } else if urlString.lowercased().hasPrefix("www.") {
                answer = "https://\(urlString)"
            } else if !urlString.contains("://") {
                answer = "https://\(urlString)"
            } else {
                answer = urlString
            }
        }
        firstURLs.setObject(URLBox(answer), forKey: TextKey(text), cost: text.utf16.count)
        return answer
    }

    /// `firstURL(in:)` off the main thread, for `onAppear`-time preview lookups.
    static func firstURLAsync(in text: String) async -> String? {
        if let hit = firstURLs.object(forKey: TextKey(text)) { return hit.value }
        if !mightContainLink(text) { return nil }
        return await withCheckedContinuation { cont in
            queue.addOperation { cont.resume(returning: firstURL(in: text)) }
        }
    }
}
