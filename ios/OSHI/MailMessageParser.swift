//
//  MailMessageParser.swift
//  OSHI
//
//  Turns the decrypted RFC822 bytes of a mail into something the UI can show.
//
//  This runs on THIS device and nowhere else. In a zero-access mailbox the server
//  cannot parse a message for the client — it never has the plaintext — so the job
//  that a normal webmail does on the backend has to happen here.
//
//  Scope is deliberately "what real mail actually arrives as", not all of MIME:
//  headers, transfer encodings, and enough multipart to pick the readable part.
//  Attachments are surfaced with their decoded bytes, so a reader can save one to the drive.
//

import Foundation

struct ParsedMail {
    var headers: [String: String] = [:]
    var textBody: String = ""
    var htmlBody: String?
    var attachments: [Attachment] = []

    struct Attachment {
        let filename: String
        let mimeType: String
        let bytes: Int
        /// The decoded attachment bytes, kept so the reader can save a piece jointe
        /// straight into the encrypted drive without a round-trip through the server.
        let data: Data
        /// `Content-ID` without its angle brackets, when the part has one — what an
        /// HTML body's `<img src="cid:…">` points at (multipart/related).
        var contentId: String? = nil

        var isImage: Bool {
            if mimeType.hasPrefix("image/") { return true }
            let ext = (filename as NSString).pathExtension.lowercased()
            return ["jpg", "jpeg", "png", "gif", "heic", "heif", "webp", "bmp", "tif", "tiff"].contains(ext)
        }
    }

    /// The attachment an HTML `cid:` reference points at. Content-IDs are compared
    /// case-insensitively: senders are not consistent about the case of the domain part
    /// between the header and the reference.
    func attachment(forContentId cid: String) -> Attachment? {
        let key = cid.trimmingCharacters(in: CharacterSet(charactersIn: "<> ")).lowercased()
        return attachments.first { $0.contentId?.lowercased() == key }
    }

    var subject: String { headers["subject"] ?? "" }
    var from: String    { headers["from"] ?? "" }
    var to: String      { headers["to"] ?? "" }
    var date: String    { headers["date"] ?? "" }
}

enum MailMessageParser {

    static func parse(_ data: Data) -> ParsedMail {
        // Mail is not guaranteed valid UTF-8 — a Latin-1 body from an older sender
        // would decode to nil and lose the whole message, so fall back rather than fail.
        let raw = String(data: data, encoding: .utf8)
            ?? String(data: data, encoding: .isoLatin1)
            ?? ""

        var mail = ParsedMail()
        let (headerBlock, body) = splitHeaders(raw)
        mail.headers = parseHeaders(headerBlock)

        let contentType = mail.headers["content-type"] ?? "text/plain"
        let encoding = mail.headers["content-transfer-encoding"] ?? "7bit"

        if contentType.lowercased().contains("multipart/"),
           let boundary = value(of: "boundary", in: contentType) {
            parseMultipart(body, boundary: boundary,
                           subtype: multipartSubtype(contentType), into: &mail)
        } else if contentType.lowercased().contains("text/html") {
            mail.htmlBody = decode(body, encoding: encoding, charset: charset(of: contentType))
            mail.textBody = stripHTML(mail.htmlBody ?? "")
        } else {
            mail.textBody = plainText(body, encoding: encoding, contentType: contentType)
        }

        return mail
    }

    // MARK: Headers

    private static func splitHeaders(_ raw: String) -> (String, String) {
        // Senders disagree about line endings; accept both rather than returning an
        // empty body for a message that used bare LF.
        if let r = raw.range(of: "\r\n\r\n") {
            return (String(raw[..<r.lowerBound]), String(raw[r.upperBound...]))
        }
        if let r = raw.range(of: "\n\n") {
            return (String(raw[..<r.lowerBound]), String(raw[r.upperBound...]))
        }
        return (raw, "")
    }

    /// A message containing binary attachments is not valid UTF-8, so `parse` falls back
    /// to Latin-1 for the whole thing — and a header carrying RAW UTF-8 (a display name
    /// like "Boutique Lumière" written without an encoded-word, which real senders do)
    /// then renders as "LumiÃ¨re". Re-decode strictly as UTF-8 and keep the result only
    /// if it really was UTF-8; a genuine Latin-1 header fails and is left alone.
    static func recoverUTF8(_ value: String) -> String {
        guard value.unicodeScalars.contains(where: { $0.value > 0x7F && $0.value <= 0xFF }) else { return value }
        var bytes = [UInt8]()
        bytes.reserveCapacity(value.unicodeScalars.count)
        for scalar in value.unicodeScalars {
            guard scalar.value <= 0xFF else { return value }   // already decoded text
            bytes.append(UInt8(scalar.value))
        }
        // String(validatingUTF8-style): only accept a clean decode.
        guard let recovered = String(bytes: bytes, encoding: .utf8) else { return value }
        return recovered
    }

    private static func parseHeaders(_ block: String) -> [String: String] {
        // Unfold first: a header continued on an indented line is one logical header,
        // and matching line-by-line truncates every long Subject and To.
        let unfolded = block
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\n ", with: " ")
            .replacingOccurrences(of: "\n\t", with: " ")

        var out: [String: String] = [:]
        for line in unfolded.split(separator: "\n") {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let name = line[..<colon].lowercased().trimmingCharacters(in: .whitespaces)
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            // The server already decoded encoded-words for the envelope, but the full
            // message is stored exactly as it arrived, so decode again here.
            out[name] = decodeEncodedWords(recoverUTF8(value))
        }
        return out
    }

    /// RFC 2047: "=?UTF-8?B?...?=" / "=?UTF-8?Q?...?=".
    static func decodeEncodedWords(_ s: String) -> String {
        guard s.contains("=?") else { return s }
        let pattern = #"=\?([^?]+)\?([BbQq])\?([^?]*)\?="#
        guard let re = try? NSRegularExpression(pattern: pattern) else { return s }

        var result = ""
        var last = s.startIndex
        var previousWasEncoded = false

        for m in re.matches(in: s, range: NSRange(s.startIndex..., in: s)) {
            guard let range = Range(m.range, in: s),
                  let csR = Range(m.range(at: 1), in: s),
                  let encR = Range(m.range(at: 2), in: s),
                  let txtR = Range(m.range(at: 3), in: s) else { continue }

            var gap = String(s[last..<range.lowerBound])
            // Whitespace separating two encoded-words is padding, not content.
            if previousWasEncoded && gap.trimmingCharacters(in: .whitespaces).isEmpty { gap = "" }
            result += gap

            let charset = String(s[csR])
            let text = String(s[txtR])
            let bytes: Data?
            if String(s[encR]).uppercased() == "B" {
                bytes = Data(base64Encoded: text)
            } else {
                bytes = decodeQuotedPrintable(text.replacingOccurrences(of: "_", with: " "))
            }
            // Any IANA charset, not just utf-8 and latin-1: a subject encoded in
            // windows-1252 used to be shown as its raw "=?windows-1252?Q?…?=" source.
            let enc = encoding(forCharset: charset)
            result += bytes.flatMap { String(data: $0, encoding: enc) } ?? String(s[range])

            last = range.upperBound
            previousWasEncoded = true
        }
        result += String(s[last...])
        return result
    }

    // MARK: Bodies

    /// The bytes a part actually carries, before any charset is applied.
    ///
    /// The `default` case matters more than it looks: an 8bit or 7bit body used to be
    /// returned as-is, so the declared charset was never applied to it at all and a
    /// windows-1252 or koi8-r message rendered as whatever `parse` had guessed. Going
    /// through Latin-1 here is not an encoding choice — it is the byte-preserving inverse
    /// of how `parse` read the message, one character per byte.
    private static func bodyBytes(_ body: String, encoding: String) -> Data {
        switch encoding.lowercased().trimmingCharacters(in: .whitespaces) {
        case "base64":
            // `.ignoreUnknownCharacters` skips the line breaks in place; splitting a
            // 30 MB body into lines and joining them again cost seconds on a phone.
            return Data(base64Encoded: body, options: .ignoreUnknownCharacters) ?? Data(body.utf8)
        case "quoted-printable":
            return decodeQuotedPrintable(body) ?? Data(body.utf8)
        default:
            return body.data(using: .isoLatin1) ?? Data(body.utf8)
        }
    }

    private static func decode(_ body: String, encoding: String, charset: String.Encoding) -> String {
        let bytes = bodyBytes(body, encoding: encoding)
        return String(data: bytes, encoding: charset)
            ?? String(data: bytes, encoding: .isoLatin1)
            ?? body
    }

    static func decodeQuotedPrintable(_ s: String) -> Data? {
        var out = Data()
        // A trailing "=" means the line was soft-wrapped by the sender: join, don't emit.
        let joined = s.replacingOccurrences(of: "=\r\n", with: "")
                      .replacingOccurrences(of: "=\n", with: "")
        var i = joined.startIndex
        while i < joined.endIndex {
            let c = joined[i]
            if c == "=", joined.index(i, offsetBy: 2, limitedBy: joined.endIndex) != nil {
                let hexStart = joined.index(after: i)
                let hexEnd = joined.index(i, offsetBy: 3)
                if let byte = UInt8(joined[hexStart..<hexEnd], radix: 16) {
                    out.append(byte)
                    i = hexEnd
                    continue
                }
            }
            out.append(contentsOf: Array(String(c).utf8))
            i = joined.index(after: i)
        }
        return out
    }

    private static func multipartSubtype(_ contentType: String) -> String {
        let main = contentType.components(separatedBy: ";")[0].lowercased()
        return main.components(separatedBy: "/").last?.trimmingCharacters(in: .whitespaces) ?? "mixed"
    }

    /// `subtype` is the multipart flavour these parts belong to. It decides what two
    /// readable parts in a row mean: in `alternative` they are the SAME text twice (keep
    /// one), in `mixed` they are consecutive pieces (keep both — Apple Mail sends a
    /// photo between two text/plain parts, and the text after the photo used to vanish).
    private static func parseMultipart(_ body: String, boundary: String, subtype: String,
                                       into mail: inout ParsedMail) {
        // Index 0 is the preamble ("This is a multi-part message in MIME format.") and
        // is never content — parsing it as a part showed it as the body and hid the real
        // text/plain behind it. A part starting with "--" follows the closing delimiter.
        let parts = body.components(separatedBy: "--\(boundary)").dropFirst()
        for part in parts {
            if part.hasPrefix("--") { break }
            let trimmed = part.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { continue }

            // The CRLF immediately before a boundary belongs to the DELIMITER, not to
            // the part, so it must come off — otherwise every multipart message renders
            // with a trailing blank line, and a one-line body looks like two.
            // removeLast() ONCE, not twice: Swift treats CRLF as a single grapheme
            // cluster, so removeLast(2) here silently eats the last real character of
            // the body as well.
            var part = part
            if part.hasSuffix("\r\n") || part.hasSuffix("\n") { part.removeLast() }

            let (head, content) = splitHeaders(part)
            let headers = parseHeaders(head)
            // Parameters are read from the RAW header: a lowercased copy turned
            // "Apple-Mail=_A1B2" into "apple-mail=_a1b2", so every nested multipart
            // with an upper-case boundary (Apple Mail's, Outlook's) came back empty.
            let rawType = headers["content-type"] ?? "text/plain"
            let type = rawType.lowercased()
            let mime = type.components(separatedBy: ";")[0].trimmingCharacters(in: .whitespaces)
            let enc = headers["content-transfer-encoding"] ?? "7bit"
            let disposition = headers["content-disposition"] ?? ""
            let filename = parameter("filename", in: disposition) ?? parameter("name", in: rawType)
            let isAttachment = disposition.lowercased().hasPrefix("attachment")
            let contentId = headers["content-id"]?
                .trimmingCharacters(in: CharacterSet(charactersIn: "<> \t"))

            if mime.hasPrefix("multipart/"), let inner = value(of: "boundary", in: rawType) {
                parseMultipart(content, boundary: inner, subtype: multipartSubtype(rawType), into: &mail)
            } else if !isAttachment, filename == nil, mime == "text/html" {
                let html = decode(content, encoding: enc, charset: charset(of: type))
                if subtype == "mixed", let existing = mail.htmlBody {
                    mail.htmlBody = existing + "\n" + html
                } else {
                    mail.htmlBody = html
                }
            } else if !isAttachment, filename == nil, mime == "text/plain" || mime == "text" {
                let text = plainText(content, encoding: enc, contentType: rawType)
                if mail.textBody.isEmpty {
                    mail.textBody = text
                } else if subtype != "alternative" {
                    mail.textBody += "\n\n" + text
                }
            } else {
                // Everything else is a file: real attachments, inline images (with or
                // without a filename — a cid: image often has none), and the
                // text/calendar part Outlook puts INSIDE multipart/alternative, which
                // used to be shown as the message body when there was no text/plain.
                let decoded = bodyBytes(content, encoding: enc)
                if decoded.isEmpty { continue }
                // Outlook sends an invitation twice: as the text/calendar alternative and
                // again as invite.ics. One file, listed once.
                if mail.attachments.contains(where: { $0.mimeType == mime && $0.data == decoded }) { continue }
                mail.attachments.append(.init(filename: filename ?? defaultFilename(for: mime),
                                              mimeType: mime,
                                              bytes: decoded.count,
                                              data: decoded,
                                              contentId: contentId))
            }
        }
        if mail.textBody.isEmpty, let html = mail.htmlBody {
            mail.textBody = stripHTML(html)
        }
    }

    private static func defaultFilename(for mime: String) -> String {
        switch mime {
        case "text/calendar": return "invite.ics"
        case "message/rfc822": return "message.eml"
        case "image/jpeg": return "image.jpg"
        case "image/png": return "image.png"
        case "image/gif": return "image.gif"
        case "application/pdf": return "document.pdf"
        default: return "attachment"
        }
    }

    /// A text/plain body, with RFC 3676 `format=flowed` undone: a line ending in a space
    /// is a soft break the sender's client inserted at 72 columns, and keeping it made
    /// Thunderbird mail render on a phone as ragged half-lines.
    private static func plainText(_ body: String, encoding: String, contentType: String) -> String {
        let text = decode(body, encoding: encoding, charset: charset(of: contentType))
        guard value(of: "format", in: contentType)?.lowercased() == "flowed" else { return text }
        let delsp = value(of: "delsp", in: contentType)?.lowercased() == "yes"
        return unflow(text, delSp: delsp)
    }

    static func unflow(_ text: String, delSp: Bool = false) -> String {
        var out: [String] = []
        var current: String?
        for rawLine in text.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n") {
            var line = rawLine
            // Space-stuffing (RFC 3676 §4.4): a leading space was added by the sender.
            if line.hasPrefix(" ") { line.removeFirst() }
            let isSignatureMark = line == "-- "
            let soft = line.hasSuffix(" ") && !isSignatureMark
            if soft && delSp { line.removeLast() }
            current = (current ?? "") + line
            if !soft {
                out.append(current!)
                current = nil
            }
        }
        if let current { out.append(current) }
        return out.joined(separator: "\n")
    }

    // MARK: Small helpers

    /// Pulls `name="value"` or `name=value` out of a header's parameter list.
    static func value(of name: String, in header: String) -> String? {
        // `(?<![\\w*-])` so that asking for "name" does not match inside "filename=".
        let pattern = "(?<![\\w*-])\(name)\\s*=\\s*\"?([^\";\\r\\n]+)\"?"
        guard let re = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive),
              let m = re.firstMatch(in: header, range: NSRange(header.startIndex..., in: header)),
              let r = Range(m.range(at: 1), in: header) else { return nil }
        return String(header[r]).trimmingCharacters(in: .whitespaces)
    }

    /// A header parameter as the user should see it: RFC 2231 (`filename*=UTF-8''%C3%A9.pdf`,
    /// and its split form `filename*0*=…; filename*1*=…`) — what Gmail and Outlook send for
    /// any non-ASCII file name — then plain `name="…"`, then RFC 2047 encoded-words inside it.
    static func parameter(_ name: String, in header: String) -> String? {
        let escaped = NSRegularExpression.escapedPattern(for: name)
        let pattern = "(?<![\\w-])\(escaped)\\*(\\d+)?(\\*)?\\s*=\\s*(\"[^\"]*\"|[^;\\s]+)"
        if let re = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive) {
            let ns = header as NSString
            let matches = re.matches(in: header, range: NSRange(location: 0, length: ns.length))
            if !matches.isEmpty {
                var pieces: [(Int, Bool, String)] = []
                for m in matches {
                    let index = m.range(at: 1).location == NSNotFound ? 0 : Int(ns.substring(with: m.range(at: 1))) ?? 0
                    let encoded = m.range(at: 2).location != NSNotFound || m.range(at: 1).location == NSNotFound
                    let raw = ns.substring(with: m.range(at: 3)).trimmingCharacters(in: CharacterSet(charactersIn: "\""))
                    pieces.append((index, encoded, raw))
                }
                pieces.sort { $0.0 < $1.0 }
                var charset = "utf-8"
                var bytes = Data()
                for (i, piece) in pieces.enumerated() {
                    var raw = piece.2
                    if piece.1 {
                        if i == 0 {
                            // charset'language'value
                            let fields = raw.components(separatedBy: "'")
                            if fields.count >= 3 {
                                charset = fields[0].isEmpty ? "utf-8" : fields[0]
                                raw = fields[2...].joined(separator: "'")
                            }
                        }
                        bytes.append(percentDecoded(raw))
                    } else {
                        bytes.append(contentsOf: Array(raw.utf8))
                    }
                }
                if let decoded = String(data: bytes, encoding: encoding(forCharset: charset)) ?? String(data: bytes, encoding: .isoLatin1),
                   !decoded.isEmpty {
                    return decoded
                }
            }
        }
        return value(of: name, in: header).map(decodeEncodedWords)
    }

    private static func percentDecoded(_ s: String) -> Data {
        var out = Data()
        var i = s.startIndex
        while i < s.endIndex {
            if s[i] == "%", let end = s.index(i, offsetBy: 3, limitedBy: s.endIndex),
               let byte = UInt8(s[s.index(after: i)..<end], radix: 16) {
                out.append(byte)
                i = end
            } else {
                out.append(contentsOf: Array(String(s[i]).utf8))
                i = s.index(after: i)
            }
        }
        return out
    }

    /// Resolve the charset a message declares to a real `String.Encoding`.
    ///
    /// A hand-written table of "the charsets we expect" was wrong twice on the web side:
    /// a substring test for "8859-1" also matched iso-8859-15, turning its euro sign into
    /// the currency sign ¤, and any label outside the table was not decoded at all —
    /// windows-1252 subjects, which Outlook sends constantly, stayed as raw
    /// "=?windows-1252?Q?…?=" text. CoreFoundation already maps the whole IANA registry,
    /// so the declared name goes straight to it.
    static func encoding(forCharset name: String?) -> String.Encoding {
        guard var cs = name?.trimmingCharacters(in: CharacterSet(charactersIn: " \"'")).lowercased(),
              !cs.isEmpty else { return .utf8 }
        // RFC 2231 allows a language suffix on the charset ("utf-8*fr").
        cs = cs.components(separatedBy: "*")[0]

        let cfEncoding = CFStringConvertIANACharSetNameToEncoding(cs as CFString)
        guard cfEncoding != kCFStringEncodingInvalidId else { return .utf8 }
        return String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(cfEncoding))
    }

    private static func charset(of contentType: String) -> String.Encoding {
        encoding(forCharset: value(of: "charset", in: contentType))
    }

    static func stripHTML(_ html: String) -> String {
        var s = html
        for tag in ["script", "style", "head"] {
            s = s.replacingOccurrences(of: "<\(tag)[\\s\\S]*?</\(tag)>", with: " ",
                                       options: [.regularExpression, .caseInsensitive])
        }
        s = s.replacingOccurrences(of: "<br\\s*/?>", with: "\n", options: [.regularExpression, .caseInsensitive])
        // Every BLOCK element ends a line, not just </p>. Handling paragraphs alone ran a
        // heading straight into the text after it ("Merci !Votre commande…").
        s = s.replacingOccurrences(
            of: "</(p|div|h[1-6]|li|tr|blockquote|section|article|header|footer|ul|ol|table)>",
            with: "\n\n", options: [.regularExpression, .caseInsensitive])
        s = s.replacingOccurrences(of: "<(li|tr)\\b[^>]*>", with: "\n",
                                   options: [.regularExpression, .caseInsensitive])
        s = s.replacingOccurrences(of: "<!--[\\s\\S]*?-->", with: "", options: .regularExpression)
        s = s.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        s = decodeEntities(s)
        return s.replacingOccurrences(of: "[ \\t]{2,}", with: " ", options: .regularExpression)
                .replacingOccurrences(of: "\n{3,}", with: "\n\n", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Named entities newsletters actually use, plus every numeric one (`&#8217;`,
    /// `&#x2019;`) — those used to reach the search cache and the text fallback raw.
    static func decodeEntities(_ input: String) -> String {
        guard input.contains("&") else { return input }
        var s = input
        if let re = try? NSRegularExpression(pattern: "&#(x[0-9a-fA-F]+|[0-9]+);") {
            let ns = s as NSString
            var out = ""
            var last = 0
            for m in re.matches(in: s, range: NSRange(location: 0, length: ns.length)) {
                out += ns.substring(with: NSRange(location: last, length: m.range.location - last))
                let body = ns.substring(with: m.range(at: 1))
                let value = body.hasPrefix("x") || body.hasPrefix("X")
                    ? UInt32(body.dropFirst(), radix: 16) : UInt32(body)
                if let value, let scalar = Unicode.Scalar(value) {
                    out.unicodeScalars.append(scalar)
                } else {
                    out += ns.substring(with: m.range)
                }
                last = m.range.location + m.range.length
            }
            out += ns.substring(from: last)
            s = out
        }
        let entities = ["&nbsp;": " ", "&lt;": "<", "&gt;": ">", "&quot;": "\"", "&apos;": "'",
                        "&mdash;": "—", "&ndash;": "–", "&hellip;": "…", "&rsquo;": "’",
                        "&lsquo;": "‘", "&rdquo;": "”", "&ldquo;": "“", "&euro;": "€",
                        "&copy;": "©", "&reg;": "®", "&trade;": "™", "&zwnj;": "", "&shy;": ""]
        for (e, c) in entities { s = s.replacingOccurrences(of: e, with: c) }
        // Last, so "&amp;lt;" becomes "&lt;" and not "<".
        return s.replacingOccurrences(of: "&amp;", with: "&")
    }

    /// Longest list snippet; the server caps the ones it seals at the same length.
    static let snippetMax = 140

    /// Whitespace-collapsed, length-capped preview of text the user typed (draft envelopes).
    static func listSnippet(of text: String, max: Int = snippetMax) -> String {
        let flat = text.components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.joined(separator: " ")
        guard flat.count > max else { return flat }
        var cut = String(flat.prefix(max))
        if let space = cut.lastIndex(of: " "), cut.distance(from: cut.startIndex, to: space) >= max * 6 / 10 {
            cut = String(cut[..<space])
        }
        while let last = cut.last, " ,;:.".contains(last) { cut.removeLast() }
        return cut + "\u{2026}"
    }

    /// "Jane Doe <jane@x.com>" -> "Jane Doe"; a bare address stays as it is.
    static func displayName(from address: String) -> String {
        let t = address.trimmingCharacters(in: .whitespaces)
        if let open = t.firstIndex(of: "<"), open != t.startIndex {
            let name = t[..<open].trimmingCharacters(in: CharacterSet(charactersIn: " \"'"))
            if !name.isEmpty { return name }
        }
        if let open = t.firstIndex(of: "<"), let close = t.firstIndex(of: ">"), open < close {
            return String(t[t.index(after: open)..<close])
        }
        return t
    }

    /// The bare address, for replying.
    static func emailAddress(from address: String) -> String {
        let t = address.trimmingCharacters(in: .whitespaces)
        if let open = t.firstIndex(of: "<"), let close = t.firstIndex(of: ">"), open < close {
            return String(t[t.index(after: open)..<close])
        }
        return t
    }
}
