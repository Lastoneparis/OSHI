//
//  MessageFormatting.swift
//  OSHI — **bold** and __underline__ inside a message (shared spec with Android).
//
//  __TEXT_FORMATTING_2026_11_24__ User request: "bold and underlined text in
//  OSHI messages and groups, on both platforms". The formatting travels INSIDE
//  the message text as markers — no envelope or protocol change — so an old
//  client shows the raw markers and loses nothing.
//
//    `**text**` -> bold        `__text__` -> underline
//    nesting (`**__x__**`, `__**x**__`) -> both
//
//  Rules (identical on Android, pinned by the same golden vectors in both test
//  suites — `MessageFormattingTests`):
//   1. a span opens at `**` / `__` and closes at the NEXT identical marker on
//      the SAME line;
//   2. its content is non-empty and does not start or end with a space
//      (U+0020 only, as on Android);
//   3. it never crosses a newline;
//   4. unmatched markers stay literal;
//   5. markers inside a URL (`http://`, `https://` or `www.`, up to the next
//      whitespace, trailing `*` / `_` trimmed) are not formatting, so
//      `https://x.com/a__b__c` stays a link and `**https://x.com**` is a bold
//      link.
//  After a failed opener the scan advances ONE character; a span closes at
//  the very next identical marker, and an empty span is literal even when a
//  later marker exists.
//  Ranges are UTF-16 offsets into the RENDERED text, half-open — the same unit
//  as `NSString` and Kotlin `String`.
//
//  This parser handles the two-character markers only. The older single
//  markers (`*bold*`, `_italic_`, `~strike~`, `` `code` ``) are iOS-only and
//  stay in `InlineMarkup`, which runs this pass first and then its own.
//

import Foundation

enum MessageFormatting {

    struct Result: Equatable {
        /// The text as displayed: every marker that formed a span removed.
        let text: String
        /// Half-open UTF-16 ranges in `text`.
        let bold: [NSRange]
        let underline: [NSRange]
    }

    private static let star: UInt16 = 0x2A        // *
    private static let underscore: UInt16 = 0x5F  // _
    private static let newline: UInt16 = 0x0A
    private static let space: UInt16 = 0x20
    private static let carriageReturn: UInt16 = 0x0D

    /// Parses `**` / `__` spans. Cheap when the text holds no marker.
    static func parse(_ input: String) -> Result {
        let units = Array(input.utf16)
        guard hasDoubleMarker(units) else { return Result(text: input, bold: [], underline: []) }
        let protected = protectedMask(units)
        var out: [UInt16] = []
        out.reserveCapacity(units.count)
        var bold: [NSRange] = []
        var underline: [NSRange] = []
        parse(units, 0, units.count, protected, &out, &bold, &underline)
        let text = String(decoding: out, as: UTF16.self)
        return Result(text: text, bold: bold, underline: underline)
    }

    /// The text without the markers that formed spans (plain-text surfaces).
    static func stripped(_ input: String) -> String { parse(input).text }

    // MARK: - Core

    private static func hasDoubleMarker(_ u: [UInt16]) -> Bool {
        guard u.count >= 4 else { return false }
        for i in 0..<(u.count - 1) where (u[i] == star || u[i] == underscore) && u[i + 1] == u[i] {
            return true
        }
        return false
    }

    private static func isLineBreak(_ c: UInt16) -> Bool {
        c == newline || c == carriageReturn || c == 0x2028 || c == 0x2029 || c == 0x85
    }

    private static func isSpace(_ c: UInt16) -> Bool {
        guard let s = Unicode.Scalar(c) else { return false }   // lone surrogate: not a space
        return s.properties.isWhitespace
    }

    /// Recursive so nested spans (`**__x__**`) are found inside a span's content.
    private static func parse(_ u: [UInt16], _ start: Int, _ end: Int, _ protected: [Bool],
                              _ out: inout [UInt16], _ bold: inout [NSRange], _ underline: inout [NSRange]) {
        var i = start
        while i < end {
            let c = u[i]
            let isMarker = (c == star || c == underscore) && i + 1 < end && u[i + 1] == c
                && !protected[i] && !protected[i + 1]
            guard isMarker, let close = closing(u, from: i + 2, end: end, marker: c, protected) else {
                out.append(c)
                i += 1
                continue
            }
            let contentStart = i + 2
            let contentEnd = close
            let location = out.count
            parse(u, contentStart, contentEnd, protected, &out, &bold, &underline)
            let range = NSRange(location: location, length: out.count - location)
            if c == star { bold.append(range) } else { underline.append(range) }
            i = close + 2
        }
    }

    /// Index of the NEXT `marker marker` pair after `from` on the same line,
    /// provided the content between is a valid span; nil otherwise.
    private static func closing(_ u: [UInt16], from: Int, end: Int, marker: UInt16,
                                _ protected: [Bool]) -> Int? {
        var j = from
        while j + 1 < end {
            let d = u[j]
            if isLineBreak(d) { return nil }
            if d == marker, u[j + 1] == marker, !protected[j], !protected[j + 1] {
                // Rule 2: non-empty, no leading / trailing space (U+0020).
                guard j > from, u[from] != space, u[j - 1] != space else { return nil }
                return j
            }
            j += 1
        }
        return nil
    }

    // MARK: - Protected regions (URLs)

    /// True for every UTF-16 unit that sits inside a URL.
    ///
    /// A URL runs from `http://` / `https://` or a `www.` that starts a word
    /// to the next whitespace, minus any trailing `*` / `_` — so
    /// `**https://x.com**` is a bold link while `https://x.com/a__b__c` keeps its
    /// underscores.
    static func protectedMask(_ u: [UInt16]) -> [Bool] {
        var mask = [Bool](repeating: false, count: u.count)
        let n = u.count
        func isAlnum(_ c: UInt16) -> Bool {
            (c >= 0x30 && c <= 0x39) || (c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A)
        }
        func lower(_ c: UInt16) -> UInt16 { (c >= 0x41 && c <= 0x5A) ? c + 32 : c }
        func markURL(from s: Int, scanFrom: Int) {
            var e = scanFrom
            while e < n, !isSpace(u[e]) { e += 1 }
            while e > s, u[e - 1] == star || u[e - 1] == underscore { e -= 1 }
            for k in s..<e { mask[k] = true }
        }
        var i = 0
        while i < n {
            // http:// or https:// (case-insensitive), not inside a longer word
            if lower(u[i]) == 0x68, i == 0 || !isAlnum(u[i - 1]) {
                let h: [UInt16] = [0x68, 0x74, 0x74, 0x70]   // "http"
                var k = 0
                while k < 4, i + k < n, lower(u[i + k]) == h[k] { k += 1 }
                if k == 4 {
                    var p = i + 4
                    if p < n, lower(u[p]) == 0x73 { p += 1 }   // "s"
                    if p + 2 < n, u[p] == 0x3A, u[p + 1] == 0x2F, u[p + 2] == 0x2F {
                        markURL(from: i, scanFrom: p)
                        while i < n, !isSpace(u[i]) { i += 1 }
                        continue
                    }
                }
            }
            // www. at the start of a word
            if i + 3 < n, lower(u[i]) == 0x77, lower(u[i + 1]) == 0x77, lower(u[i + 2]) == 0x77,
               u[i + 3] == 0x2E, i == 0 || !(isAlnum(u[i - 1]) || u[i - 1] == 0x2E) {
                markURL(from: i, scanFrom: i)
                while i < n, !isSpace(u[i]) { i += 1 }
                continue
            }
            i += 1
        }
        return mask
    }
}

// MARK: - Composer actions

/// What the composer's Bold / Underline actions do to the draft. Pure, so the
/// behaviour is pinned by tests; `ComposerFormatBridge` applies it to the live
/// text field.
enum ComposerFormatting {

    enum Style: CaseIterable {
        case bold, underline
        var marker: String { self == .bold ? "**" : "__" }
    }

    struct Edit: Equatable {
        let text: String
        /// UTF-16 selection to restore in the field after the edit.
        let selection: NSRange
    }

    /// - With a selection: wraps it (`**sel**`) and keeps the inner text
    ///   selected. Leading / trailing whitespace of the selection stays OUTSIDE
    ///   the markers (a span may not start or end with a space). A selection
    ///   already wrapped in this marker is unwrapped instead (toggle).
    /// - Without one: inserts the empty pair and puts the caret between.
    static func apply(_ style: Style, to text: String, selection: NSRange) -> Edit {
        let ns = text as NSString
        let m = style.marker
        let mLen = (m as NSString).length
        let loc = min(max(0, selection.location), ns.length)
        let len = min(max(0, selection.length), ns.length - loc)

        if len > 0 {
            var s = loc, e = loc + len
            while s < e, isSpace(ns.character(at: s)) { s += 1 }
            while e > s, isSpace(ns.character(at: e - 1)) { e -= 1 }
            if s < e {
                // Toggle off: the markers sit right around the selection.
                if s >= mLen, e + mLen <= ns.length,
                   ns.substring(with: NSRange(location: s - mLen, length: mLen)) == m,
                   ns.substring(with: NSRange(location: e, length: mLen)) == m {
                    let inner = ns.substring(with: NSRange(location: s, length: e - s))
                    let out = ns.replacingCharacters(in: NSRange(location: s - mLen, length: e - s + 2 * mLen),
                                                     with: inner)
                    return Edit(text: out, selection: NSRange(location: s - mLen, length: e - s))
                }
                let inner = ns.substring(with: NSRange(location: s, length: e - s))
                let out = ns.replacingCharacters(in: NSRange(location: s, length: e - s), with: m + inner + m)
                return Edit(text: out, selection: NSRange(location: s + mLen, length: e - s))
            }
            // Whitespace-only selection: insert the pair at its end.
            let out = ns.replacingCharacters(in: NSRange(location: loc + len, length: 0), with: m + m)
            return Edit(text: out, selection: NSRange(location: loc + len + mLen, length: 0))
        }

        let out = ns.replacingCharacters(in: NSRange(location: loc, length: 0), with: m + m)
        return Edit(text: out, selection: NSRange(location: loc + mLen, length: 0))
    }

    private static func isSpace(_ c: unichar) -> Bool {
        guard let s = Unicode.Scalar(c) else { return false }
        return s.properties.isWhitespace
    }
}
