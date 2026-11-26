//
//  InlineMarkup.swift
//  OSHI — *bold*, _italic_, ~strike~ and `code` inside a message.
//
//  A long message with no shape is hard to read, and every messenger the user
//  already has solves it the same way: a couple of punctuation markers around a
//  run of text. This parses that syntax and returns the text WITHOUT the markers
//  plus the ranges to style, so the markers never reach the screen.
//
//  Deliberately conservative. This runs on every message anyone sends, including
//  text the sender never intended as formatting — file_name_with_underscores,
//  a*b*c in a maths note, a shell command in backticks. Rules that turn ordinary
//  punctuation into invisible formatting are worse than no formatting at all,
//  so a marker only counts when it looks like it was meant:
//
//    * the opening marker is preceded by a boundary (start, space, punctuation),
//    * the closing marker is followed by one,
//    * the run between them is non-empty, has no newline, and does not
//      start or end with a space.
//
//  That last rule is what stops an unmatched marker from swallowing the rest of
//  a paragraph — the failure mode where a stray asterisk bolds everything after
//  it.
//

import Foundation

enum InlineMarkup {

    enum Kind {
        case bold, italic, strikethrough, code
        /// `__text__` only (cross-platform, see `MessageFormatting`).
        case underline
    }

    /// A styled run, expressed in UTF-16 offsets into the RETURNED plain text so
    /// it can be converted straight to an `NSRange`.
    struct Span {
        let kind: Kind
        let location: Int
        let length: Int
    }

    private static let markers: [(Character, Kind)] = [
        ("*", .bold),
        ("_", .italic),
        ("~", .strikethrough),
        ("`", .code),
    ]

    /// Characters that may sit immediately outside a marker. Anything else (a
    /// letter, a digit) means the marker is part of a word — `snake_case_name`
    /// must not become italic.
    private static func isBoundary(_ c: Character?) -> Bool {
        guard let c else { return true }        // start or end of string
        return c.isWhitespace || c.isPunctuation || c.isSymbol
    }

    /// - Returns: the text with markers removed, and the runs to style.
    ///
    /// __TEXT_FORMATTING_2026_11_24__ Two passes. First the cross-platform
    /// `**bold**` / `__underline__` (`MessageFormatting`, same rules and golden
    /// vectors as Android); then the iOS single markers below, on what is left.
    /// The first pass's ranges are shifted by the markers the second removes, so
    /// every span ends up in the coordinates of the RETURNED text. Markers
    /// inside a URL or an `@[…]` token are never formatting, in either pass.
    static func parse(_ input: String) -> (String, [Span]) {
        // Cheap bail-out: the overwhelming majority of messages contain no
        // marker at all, and this runs on every one of them.
        guard input.contains(where: { c in markers.contains(where: { $0.0 == c }) }) else {
            return (input, [])
        }

        let doubled = MessageFormatting.parse(input)
        let (out, singleSpans, removed) = parseSingle(doubled.text)

        // Shift a first-pass offset past the single markers removed before it.
        func shift(_ offset: Int) -> Int {
            var lo = 0, hi = removed.count
            while lo < hi {   // count of removed positions < offset
                let mid = (lo + hi) / 2
                if removed[mid] < offset { lo = mid + 1 } else { hi = mid }
            }
            return offset - lo
        }
        func mapped(_ r: NSRange, _ kind: Kind) -> Span {
            let s = shift(r.location), e = shift(r.location + r.length)
            return Span(kind: kind, location: s, length: e - s)
        }
        var spans = doubled.bold.map { mapped($0, .bold) }
        spans += doubled.underline.map { mapped($0, .underline) }
        spans += singleSpans
        return (out, spans)
    }

    /// The original single-marker pass. Also returns the UTF-16 offsets (in
    /// `input`) of every marker it removed, ascending.
    private static func parseSingle(_ input: String) -> (String, [Span], [Int]) {
        guard input.contains(where: { c in markers.contains(where: { $0.0 == c }) }) else {
            return (input, [], [])
        }
        let protected = MessageFormatting.protectedMask(Array(input.utf16))

        var out = ""
        var spans: [Span] = []
        var removed: [Int] = []
        var i = input.startIndex
        var offset = 0   // UTF-16 offset of `i` in `input`

        while i < input.endIndex {
            let c = input[i]

            guard let kind = markers.first(where: { $0.0 == c })?.1, !protected[offset] else {
                out.append(c)
                offset += c.utf16.count
                i = input.index(after: i)
                continue
            }

            // Opening marker must follow a boundary.
            let before = out.last
            guard isBoundary(before) else {
                out.append(c)
                offset += 1
                i = input.index(after: i)
                continue
            }

            // Find a closing marker on the SAME line with content in between.
            var j = input.index(after: i)
            var jOffset = offset + 1
            var closing: String.Index?
            var closingOffset = 0
            while j < input.endIndex {
                let d = input[j]
                if d.isNewline { break }
                if d == c {
                    let after = input.index(after: j) < input.endIndex ? input[input.index(after: j)] : nil
                    if j > input.index(after: i), isBoundary(after), !protected[jOffset] {
                        closing = j
                        closingOffset = jOffset
                    }
                    break
                }
                jOffset += d.utf16.count
                j = input.index(after: j)
            }

            guard let close = closing else {
                // No valid partner — emit the marker as the literal character it
                // is. This is the case that protects `2 * 3 * 4` and a lone `_`.
                out.append(c)
                offset += 1
                i = input.index(after: i)
                continue
            }

            let inner = String(input[input.index(after: i)..<close])

            // The run must not START or END with whitespace. This is the rule
            // every other messenger uses, and the reason is concrete: in
            // `2 * 3 * 4 = 24` the two asterisks DO match, with " 3 " between
            // them, so a naive parser renders `2  3  4 = 24` — silently deleting
            // characters from an ordinary arithmetic message. Requiring the
            // marker to sit tight against its text is what separates `*bold*`
            // from punctuation that happens to pair up.
            guard let first = inner.first, let last = inner.last,
                  !first.isWhitespace, !last.isWhitespace else {
                out.append(c)
                offset += 1
                i = input.index(after: i)
                continue
            }

            let location = out.utf16.count
            out += inner
            spans.append(Span(kind: kind, location: location, length: inner.utf16.count))
            removed.append(offset)
            removed.append(closingOffset)
            offset = closingOffset + 1
            i = input.index(after: close)
        }

        return (out, spans, removed)
    }

    /// The message as it will be SEEN, with the markers gone and nothing styled.
    ///
    /// For the surfaces that show a message but cannot style it: the reply quote
    /// box, the pinned banner, a conversation row's last line, a notification.
    /// Those drew the raw string, so a reply to `*gras*` quoted it with its
    /// asterisks still attached — the one place the markers are guaranteed to be
    /// visible is the place the feature is supposed to hide them.
    ///
    /// Deliberately NOT folded into `OSHIControlPayload.previewText`: the group
    /// bubble runs its body through that call before handing it to
    /// `ClickableTextView`, so stripping there would delete the markers before
    /// anything could style them and turn every bold message plain.
    static func stripped(_ input: String) -> String {
        parse(input).0
    }

    /// True when the text contains at least one run this parser would style.
    /// Used by the composer to decide whether to show a formatting hint.
    static func hasFormatting(_ input: String) -> Bool {
        !parse(input).1.isEmpty
    }
}
