//
//  MailMessageParserTests.swift
//  OSHITests
//
//  On a zero-access mailbox the server cannot parse mail for the client, so this parser
//  is the ONLY thing standing between arriving bytes and what the user reads. Every case
//  below is a shape real mail actually arrives in; two of them (the multipart trailing
//  CRLF, and CRLF counting as one Swift Character) were live bugs this file caught.
//

import XCTest
@testable import OSHI

final class MailMessageParserTests: XCTestCase {

    // MARK: What our own sender produces

    func testOurOwnSenderRoundTrips() {
        let body = Data("Bonjour — ça va ?".utf8).base64EncodedString()
        let raw = """
        From: =?UTF-8?B?SsOpcsO0bWU=?= <j@x.com>\r
        To: hugo@mail.oshi-messenger.com\r
        Subject: =?UTF-8?B?Q2Fmw6kg4oCUIGFjY2VudHXDqQ==?=\r
        MIME-Version: 1.0\r
        Content-Type: text/plain; charset=UTF-8\r
        Content-Transfer-Encoding: base64\r
        \r
        \(body)
        """
        let mail = MailMessageParser.parse(Data(raw.utf8))
        XCTAssertEqual(mail.subject, "Café — accentué")
        XCTAssertEqual(mail.from, "Jérôme <j@x.com>")
        XCTAssertEqual(mail.textBody, "Bonjour — ça va ?")
        XCTAssertEqual(MailMessageParser.displayName(from: mail.from), "Jérôme")
        XCTAssertEqual(MailMessageParser.emailAddress(from: mail.from), "j@x.com")
    }

    // MARK: Transfer encodings

    func testQuotedPrintableWithSoftLineBreak() {
        let raw = """
        From: a@b.com\r
        Subject: Test\r
        Content-Type: text/plain; charset=UTF-8\r
        Content-Transfer-Encoding: quoted-printable\r
        \r
        Caf=C3=A9 and a very long line that the sender soft-wrapped right =\r
        here without a real break.
        """
        XCTAssertEqual(MailMessageParser.parse(Data(raw.utf8)).textBody,
                       "Café and a very long line that the sender soft-wrapped right here without a real break.")
    }

    func testLatin1BodyDoesNotComeBackEmpty() {
        var raw = Data("From: a@b.com\r\nSubject: Latin\r\nContent-Type: text/plain; charset=iso-8859-1\r\n\r\n".utf8)
        raw.append(contentsOf: [0x43, 0x61, 0x66, 0xE9])   // "Café" in Latin-1
        XCTAssertEqual(MailMessageParser.parse(raw).textBody, "Café")
    }

    // MARK: Multipart

    /// The CRLF before a boundary belongs to the delimiter. Keeping it appended a blank
    /// line to every multipart message; removing it with `removeLast(2)` ate the last
    /// real character, because Swift counts CRLF as ONE Character.
    func testMultipartBodyHasNoTrailingNewline() {
        let raw = """
        From: a@b.com\r
        Subject: Newsletter\r
        Content-Type: multipart/alternative; boundary="b1"\r
        \r
        --b1\r
        Content-Type: text/plain; charset=UTF-8\r
        \r
        the plain part\r
        --b1\r
        Content-Type: text/html; charset=UTF-8\r
        \r
        <html><body><p>the html part</p></body></html>\r
        --b1--\r
        """
        let mail = MailMessageParser.parse(Data(raw.utf8))
        XCTAssertEqual(mail.textBody, "the plain part", "text/plain must win, with no trailing newline")
        XCTAssertTrue(mail.htmlBody?.contains("the html part") == true)
    }

    func testAttachmentIsSurfacedWithDecodedFilename() {
        let raw = """
        From: a@b.com\r
        Subject: Invoice\r
        Content-Type: multipart/mixed; boundary="x"\r
        \r
        --x\r
        Content-Type: text/plain\r
        \r
        see attached\r
        --x\r
        Content-Type: application/pdf; name="facture.pdf"\r
        Content-Disposition: attachment; filename="=?UTF-8?B?ZmFjdHVyZSDDqXTDqS5wZGY=?="\r
        \r
        JVBERi0xLjQK\r
        --x--\r
        """
        let mail = MailMessageParser.parse(Data(raw.utf8))
        XCTAssertEqual(mail.attachments.count, 1)
        XCTAssertEqual(mail.attachments.first?.filename, "facture été.pdf")
        XCTAssertEqual(mail.textBody, "see attached")
    }

    // MARK: Headers

    func testFoldedSubjectIsNotTruncatedAtTheFold() {
        let raw = "From: a@b.com\r\nSubject: a subject that the sending server\r\n folded across two lines\r\n\r\nbody"
        XCTAssertEqual(MailMessageParser.parse(Data(raw.utf8)).subject,
                       "a subject that the sending server folded across two lines")
    }

    func testAdjacentEncodedWordsJoinWithoutASpace() {
        XCTAssertEqual(MailMessageParser.decodeEncodedWords("=?UTF-8?B?w6A=?= =?UTF-8?B?w6k=?="), "àé")
        XCTAssertEqual(MailMessageParser.decodeEncodedWords("Re: =?UTF-8?B?w6A=?= end"), "Re: à end")
        XCTAssertEqual(MailMessageParser.decodeEncodedWords("plain ascii"), "plain ascii")
    }

    // MARK: Shapes that are not conforming but do arrive

    func testBareLineFeedSeparatorStillSplitsHeadersFromBody() {
        let raw = "From: a@b.com\nSubject: Bare LF\n\nthe body"
        XCTAssertEqual(MailMessageParser.parse(Data(raw.utf8)).textBody, "the body")
    }

    func testHtmlOnlyMailRendersAsReadableText() {
        let raw = """
        From: a@b.com\r
        Subject: HTML only\r
        Content-Type: text/html; charset=UTF-8\r
        \r
        <html><head><style>p{color:red}</style></head><body><p>Hello&nbsp;there</p><p>Second&amp;line</p></body></html>
        """
        XCTAssertEqual(MailMessageParser.parse(Data(raw.utf8)).textBody, "Hello there\n\nSecond&line")
    }
}

// MARK: - Bugs found by reading real mail in the web client

extension MailMessageParserTests {

    /// A message with a binary attachment is not valid UTF-8, so the whole thing decodes
    /// as Latin-1 — and a raw-UTF-8 display name in From came out as "Boutique LumiÃ¨re".
    func testRawUTF8HeaderIsNotMojibake() {
        var raw = Data("From: ".utf8)
        raw.append(contentsOf: Array("Boutique Lumière".utf8))       // raw UTF-8, no encoded-word
        raw.append(contentsOf: Array(" <contact@boutique.example>\r\nSubject: Commande\r\nContent-Type: multipart/mixed; boundary=\"bb\"\r\n\r\n--bb\r\nContent-Type: text/plain\r\n\r\nbody\r\n--bb\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=\"x.bin\"\r\nContent-Transfer-Encoding: base64\r\n\r\n".utf8))
        raw.append(contentsOf: Array("//79/A==".utf8))               // bytes that are not valid UTF-8
        raw.append(contentsOf: Array("\r\n--bb--\r\n".utf8))

        let mail = MailMessageParser.parse(raw)
        XCTAssertEqual(mail.from, "Boutique Lumière <contact@boutique.example>")
        XCTAssertFalse(mail.from.contains("Ã"), "the header must not be read as Latin-1")
    }

    /// A Latin-1 header must NOT be mangled by the UTF-8 recovery above.
    func testGenuineLatin1HeaderIsLeftAlone() {
        XCTAssertEqual(MailMessageParser.recoverUTF8("Caf\u{E9}"), "Caf\u{E9}")
    }

    /// </h1> and friends end a line. Handling only </p> produced "Merci !Votre commande…".
    func testBlockElementsEndALine() {
        let raw = """
        From: a@b.com\r
        Subject: HTML\r
        Content-Type: text/html; charset=UTF-8\r
        \r
        <html><body><h1>Merci !</h1><p>Votre commande a quitté notre entrepôt.</p><ul><li>un</li><li>deux</li></ul></body></html>
        """
        let text = MailMessageParser.parse(Data(raw.utf8)).textBody
        XCTAssertFalse(text.contains("Merci !Votre"), "a heading must not run into the next block")
        XCTAssertTrue(text.contains("Merci !"))
        XCTAssertTrue(text.contains("Votre commande a quitté notre entrepôt."))
        XCTAssertTrue(text.contains("un"))
        XCTAssertTrue(text.contains("deux"))
    }
}

// MARK: - Legacy / ANSI charsets

extension MailMessageParserTests {

    private func message(charset: String, bodyBytes: [UInt8], encoding: String = "8bit") -> Data {
        var raw = Data("From: a@b.com\r\nSubject: T\r\nContent-Type: text/plain; charset=\(charset)\r\nContent-Transfer-Encoding: \(encoding)\r\n\r\n".utf8)
        raw.append(contentsOf: bodyBytes)
        return raw
    }

    /// Windows "ANSI". 0x80 is the euro sign and 0x93/0x94 are curly quotes — in
    /// ISO-8859-1 those same bytes are control characters, so mapping 1252 onto Latin-1
    /// silently turns prices and quotes into invisible characters.
    func testWindows1252Body() {
        let bytes: [UInt8] = [0x50, 0x72, 0x69, 0x78, 0x20, 0x31, 0x30, 0x30, 0x80, 0x20, 0x93, 0x6F, 0x6B, 0x94]
        XCTAssertEqual(MailMessageParser.parse(message(charset: "windows-1252", bodyBytes: bytes)).textBody,
                       "Prix 100€ “ok”")
    }

    /// iso-8859-15 differs from -1 in exactly one place that matters: 0xA4 is €, not ¤.
    /// A substring test for "8859-1" matches "8859-15" and gets this wrong.
    func testISO885915EuroIsNotTheCurrencySign() {
        XCTAssertEqual(MailMessageParser.parse(message(charset: "iso-8859-15", bodyBytes: [0x50, 0x72, 0x69, 0x78, 0x20, 0xA4])).textBody,
                       "Prix €")
    }

    func testPlainASCIIAndLatin1StillWork() {
        XCTAssertEqual(MailMessageParser.parse(message(charset: "us-ascii", bodyBytes: Array("Plain ASCII".utf8))).textBody,
                       "Plain ASCII")
        XCTAssertEqual(MailMessageParser.parse(message(charset: "iso-8859-1", bodyBytes: [0x43, 0x61, 0x66, 0xE9])).textBody,
                       "Café")
    }

    func testNonLatinLegacyCharsets() {
        XCTAssertEqual(MailMessageParser.parse(message(charset: "koi8-r", bodyBytes: [0xF0, 0xD2, 0xC9, 0xD7, 0xC5, 0xD4])).textBody,
                       "Привет")
        XCTAssertEqual(MailMessageParser.parse(message(charset: "iso-8859-2", bodyBytes: [0x5A, 0x65, 0x6C, 0x65, 0x6E, 0xE1])).textBody,
                       "Zelená")
    }

    /// A subject encoded in windows-1252 — what Outlook emits by default — used to be
    /// shown to the user as its raw "=?windows-1252?Q?…?=" source.
    func testEncodedWordInALegacyCharset() {
        XCTAssertEqual(MailMessageParser.decodeEncodedWords("=?windows-1252?Q?Prix_=80100?="), "Prix €100")
        XCTAssertEqual(MailMessageParser.decodeEncodedWords("=?ISO-8859-2?Q?Zelen=E1?="), "Zelená")
    }

    /// An unknown label must not lose the message.
    func testUnknownCharsetFallsBackInsteadOfFailing() {
        XCTAssertEqual(MailMessageParser.parse(message(charset: "x-nonsense", bodyBytes: Array("hi".utf8))).textBody, "hi")
    }

    /// None of the charset handling may touch attachment bytes.
    func testAttachmentBytesSurviveALegacyCharsetMessage() {
        let binary: [UInt8] = [0, 1, 2, 250, 251, 252, 253, 254, 255]
        let b64 = Data(binary).base64EncodedString()
        let raw = Data("""
        From: a@b.com\r
        Subject: A\r
        Content-Type: multipart/mixed; boundary="b"\r
        \r
        --b\r
        Content-Type: text/plain; charset=windows-1252\r
        \r
        hi\r
        --b\r
        Content-Type: application/octet-stream\r
        Content-Transfer-Encoding: base64\r
        Content-Disposition: attachment; filename="x.bin"\r
        \r
        \(b64)\r
        --b--\r
        """.utf8)
        let mail = MailMessageParser.parse(raw)
        XCTAssertEqual(mail.attachments.count, 1)
        XCTAssertEqual(mail.textBody, "hi")
    }

    // MARK: Presentation fixes (2026-11-16)

    /// The MIME preamble used to be parsed as a part and shown as the body, hiding the
    /// real text/plain behind it; and an 8bit windows-1252 body must keep its € sign.
    func testPreambleIsNotTheBodyAndCp1252BodyDecodes() {
        var raw = Data("""
        From: a@b.com\r
        Subject: =?windows-1252?Q?R=E9union_=80?=\r
        Content-Type: multipart/mixed; boundary="----=_NextPart_000"\r
        \r
        This is a multi-part message in MIME format.\r
        \r
        If you can read this, your mail client does not understand MIME.\r
        \r
        ------=_NextPart_000\r
        Content-Type: text/plain; charset="windows-1252"\r
        Content-Transfer-Encoding: 8bit\r
        \r

        """.utf8)
        raw.append(contentsOf: [0x43, 0x6F, 0xFB, 0x74, 0x20, 0x34, 0x35, 0x20, 0x80])  // "Coût 45 €"
        raw.append(Data("\r\n------=_NextPart_000--\r\n".utf8))
        let mail = MailMessageParser.parse(raw)
        XCTAssertEqual(mail.subject, "Réunion €")
        XCTAssertEqual(mail.textBody, "Coût 45 €")
    }

    /// Apple Mail's boundaries are mixed case. The nested one used to be read from a
    /// LOWERCASED Content-Type, so the related part (HTML + cid images) came back empty.
    func testNestedUpperCaseBoundaryAndContentIdImage() {
        let png = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]).base64EncodedString()
        let raw = """
        From: a@b.com\r
        Subject: Logo\r
        Content-Type: multipart/alternative; boundary="Apple-Mail=_OUTER-A1"\r
        \r
        --Apple-Mail=_OUTER-A1\r
        Content-Type: text/plain; charset=us-ascii\r
        \r
        plain version\r
        --Apple-Mail=_OUTER-A1\r
        Content-Type: multipart/related;\r
        \ttype="text/html";\r
        \tboundary="Apple-Mail=_INNER-B2"\r
        \r
        --Apple-Mail=_INNER-B2\r
        Content-Type: text/html; charset=utf-8\r
        \r
        <p>Logo: <img src="cid:Logo-1@Example.ORG"></p>\r
        --Apple-Mail=_INNER-B2\r
        Content-Type: image/png\r
        Content-Transfer-Encoding: base64\r
        Content-ID: <logo-1@example.org>\r
        \r
        \(png)\r
        --Apple-Mail=_INNER-B2--\r
        \r
        --Apple-Mail=_OUTER-A1--\r
        """
        let mail = MailMessageParser.parse(Data(raw.utf8))
        XCTAssertEqual(mail.textBody, "plain version")
        XCTAssertEqual(mail.htmlBody, #"<p>Logo: <img src="cid:Logo-1@Example.ORG"></p>"#)
        XCTAssertEqual(mail.attachments.count, 1)
        XCTAssertEqual(mail.attachments.first?.contentId, "logo-1@example.org")
        XCTAssertEqual(mail.attachments.first?.filename, "image.png")
        XCTAssertNotNil(mail.attachment(forContentId: "Logo-1@Example.ORG"), "cid lookup is case-insensitive")

        let doc = MailHTMLDocument.build(html: mail.htmlBody!, mail: mail, allowRemote: false)
        XCTAssertTrue(doc.contains("src=\"data:image/png;base64,\(png)\""), "cid: must be inlined as data:")
        XCTAssertEqual(MailHTMLDocument.referencedContentIds(in: mail.htmlBody!), ["logo-1@example.org"])
    }

    /// Apple Mail sends text, photo, text as three parts of multipart/mixed. The text
    /// after the photo used to be dropped.
    func testMixedTextImageTextKeepsBothTexts() {
        let raw = """
        From: a@b.com\r
        Subject: Photo\r
        Content-Type: multipart/mixed; boundary="Apple-Mail=_MIX"\r
        \r
        --Apple-Mail=_MIX\r
        Content-Type: text/plain; charset=us-ascii\r
        \r
        Before the photo.\r
        --Apple-Mail=_MIX\r
        Content-Disposition: inline; filename=IMG_2201.jpeg\r
        Content-Type: image/jpeg; name="IMG_2201.jpeg"\r
        Content-Transfer-Encoding: base64\r
        \r
        /9j/4AAQ\r
        --Apple-Mail=_MIX\r
        Content-Type: text/plain; charset=us-ascii\r
        \r
        After the photo.\r
        --Apple-Mail=_MIX--\r
        """
        let mail = MailMessageParser.parse(Data(raw.utf8))
        XCTAssertEqual(mail.textBody, "Before the photo.\n\nAfter the photo.")
        XCTAssertEqual(mail.attachments.map(\.filename), ["IMG_2201.jpeg"], "case of the name is kept")
        XCTAssertTrue(mail.attachments[0].isImage)
    }

    func testRFC2231FilenamesAndNameDoesNotMatchFilename() {
        XCTAssertEqual(MailMessageParser.parameter("filename", in: "attachment; filename*=UTF-8''r%C3%A9sum%C3%A9%20%E2%82%AC.pdf"),
                       "résumé €.pdf")
        XCTAssertEqual(MailMessageParser.parameter("filename", in: "attachment; filename*0*=UTF-8''Fac%C3%A9; filename*1=\"ture.pdf\""),
                       "Facéture.pdf")
        XCTAssertEqual(MailMessageParser.parameter("filename", in: #"attachment; filename="=?iso-8859-1?Q?R=E9cap.pdf?=""#),
                       "Récap.pdf")
        XCTAssertNil(MailMessageParser.value(of: "name", in: #"attachment; filename="a.pdf""#),
                     "`name` must not be read out of `filename=`")
    }

    /// Outlook puts the invitation inside multipart/alternative AND attaches it again.
    /// It used to become the message body when there was no text/plain; now it is one
    /// file, listed once.
    func testCalendarAlternativeIsAFileAndNotDuplicated() {
        let ics = Data("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n".utf8).base64EncodedString()
        let raw = """
        From: a@b.com\r
        Subject: Invite\r
        Content-Type: multipart/mixed; boundary="m"\r
        \r
        --m\r
        Content-Type: multipart/alternative; boundary="a"\r
        \r
        --a\r
        Content-Type: text/calendar; charset=utf-8; method=REQUEST\r
        Content-Transfer-Encoding: base64\r
        \r
        \(ics)\r
        --a--\r
        --m\r
        Content-Type: text/calendar; name="invite.ics"\r
        Content-Disposition: attachment; filename="invite.ics"\r
        Content-Transfer-Encoding: base64\r
        \r
        \(ics)\r
        --m--\r
        """
        let mail = MailMessageParser.parse(Data(raw.utf8))
        XCTAssertEqual(mail.textBody, "")
        XCTAssertEqual(mail.attachments.map(\.filename), ["invite.ics"])
    }

    func testFormatFlowedIsReflowed() {
        let raw = "From: a@b.com\r\nSubject: f\r\nContent-Type: text/plain; charset=utf-8; format=flowed\r\n\r\n"
            + "One paragraph that was \r\nsoft wrapped.\r\n\r\nNext.\r\n-- \r\nSig\r\n"
        XCTAssertEqual(MailMessageParser.parse(Data(raw.utf8)).textBody,
                       "One paragraph that was soft wrapped.\n\nNext.\n-- \nSig\n")
    }

    func testStripHTMLDecodesNumericEntitiesAndComments() {
        XCTAssertEqual(MailMessageParser.stripHTML("<!-- hidden --><p>It&#8217;s &#x20AC;5 &amp;lt;b&amp;gt; &mdash; ok</p>"),
                       "It’s €5 &lt;b&gt; — ok")
    }

    // MARK: HTML document (privacy)

    func testRemoteContentBlockedByDefaultAndAllowedOnRequest() {
        let html = #"<img src="https://t.example/p.gif"><div style="background:url('https://t.example/bg.png')">x</div>"#
        let mail = ParsedMail()
        XCTAssertTrue(MailHTMLDocument.hasRemoteContent(html))
        XCTAssertTrue(MailHTMLDocument.hasRemoteContent(#"<td background="http://x/y.png">"#))
        XCTAssertTrue(MailHTMLDocument.hasRemoteContent(#"<img src=//cdn.example/x.png>"#), "protocol-relative")
        XCTAssertFalse(MailHTMLDocument.hasRemoteContent(#"<img src="cid:a@b"><a href="https://x">link</a>"#),
                       "a link is not a load; cid is local")

        let blocked = MailHTMLDocument.build(html: html, mail: mail, allowRemote: false)
        XCTAssertTrue(blocked.contains("default-src 'none'; img-src data:;"))
        XCTAssertFalse(blocked.contains("img-src data: https:"))
        let allowed = MailHTMLDocument.build(html: html, mail: mail, allowRemote: true)
        XCTAssertTrue(allowed.contains("img-src data: https: http:;"))
        XCTAssertFalse(allowed.contains("script-src"), "scripts stay under default-src 'none'")
        // The CSP meta must come before any sender markup, or it does not cover it.
        XCTAssertLessThan(blocked.range(of: "Content-Security-Policy")!.lowerBound,
                          blocked.range(of: "t.example")!.lowerBound)
    }

    func testStyledMailIsKeptLightAndPlainMailFollowsTheSystem() {
        XCTAssertTrue(MailHTMLDocument.isStyled(##"<body bgcolor="#eee">"##))
        XCTAssertTrue(MailHTMLDocument.isStyled(##"<td style="color:#111">"##))
        XCTAssertFalse(MailHTMLDocument.isStyled(#"<div dir="ltr">Hi <b>there</b></div><blockquote style="border-left:1px solid rgb(204,204,204)">q</blockquote>"#))
    }

    // MARK: Plain-text layout

    func testPlainTextBlocksQuotesSignatureAndDirection() {
        let text = "Hello\n\n> quoted\n>> deeper\n\nمرحبا بك\n-- \nMarie"
        let blocks = MailPlainTextBody.blocks(text)
        XCTAssertEqual(blocks.map(\.kind), [.body, .quote(1), .quote(2), .body, .signature])
        XCTAssertEqual(blocks.map(\.text), ["Hello", "quoted", "deeper", "مرحبا بك", "Marie"])
        XCTAssertEqual(blocks.map(\.rightToLeft), [false, false, false, true, false])
        XCTAssertTrue(MailPlainTextBody.isRightToLeft("  12: שלום"))
        XCTAssertFalse(MailPlainTextBody.isRightToLeft("Привет مرحبا"))
    }

    func testAttachmentTempNameCannotEscapeTheFolder() {
        let a = ParsedMail.Attachment(filename: "../../etc/passwd", mimeType: "application/pdf", bytes: 1, data: Data([1]))
        XCTAssertEqual(MailAttachmentsSection.safeName(a), "passwd.pdf")
    }
}

/// The sealed list envelope, decoded. The server now adds `snippet`, `hasAttachments`,
/// `attachmentCount` and `hasImages` — optional and additive.
final class MailEnvelopePreviewTests: XCTestCase {

    private func decode(_ json: String) -> OSHIMailMessage.Envelope? {
        OSHIMailMessage.Envelope.decode(Data(json.utf8))
    }

    // Verbatim output of the patched server for samples/03_related_cid_and_pdf.eml.
    private let serverNew = #"{"from":"Bank <noreply@bank.example>","to":"hugo@mail.oshi-messenger.com","cc":"","subject":"Your statement","date":"Mon, 16 Nov 2026 11:19:40 GMT","messageId":"","bytes":838,"envelopeFrom":"a@b","envelopeTo":"hugo@mail.oshi-messenger.com","hasAttachments":true,"attachmentCount":1,"hasImages":true,"snippet":"Your November statement is ready."}"#
    private let serverOld = #"{"from":"Alice <a@x.io>","to":"hugo@mail.oshi-messenger.com","cc":"","subject":"Lunch?","date":"Mon, 9 Nov 2026 10:00:00 +0000","messageId":"<1@x>","bytes":512,"envelopeFrom":"a@x.io","envelopeTo":"hugo@mail.oshi-messenger.com"}"#

    func testNewEnvelopeCarriesSnippetAndFlags() throws {
        let e = try XCTUnwrap(decode(serverNew))
        XCTAssertEqual(e.subject, "Your statement")
        XCTAssertEqual(e.snippet, "Your November statement is ready.")
        XCTAssertTrue(e.hasAttachments)
        XCTAssertEqual(e.attachmentCount, 1)
        XCTAssertTrue(e.hasImages)
        XCTAssertEqual(MailRow.attachmentDescription(e), "1 attachment, contains images")
    }

    func testOldEnvelopeDecodesAsBeforeWithNoPreview() throws {
        let e = try XCTUnwrap(decode(serverOld))
        XCTAssertEqual(e.from, "Alice <a@x.io>")
        XCTAssertEqual(e.messageId, "<1@x>")
        XCTAssertEqual(e.bytes, 512)
        XCTAssertNil(e.snippet)
        XCTAssertFalse(e.hasAttachments)
        XCTAssertEqual(e.attachmentCount, 0)
        XCTAssertFalse(e.hasImages)
        XCTAssertEqual(MailRow.attachmentDescription(e), "")
    }

    func testOldSentCopiesGetAPaperclipFromTheirFilenames() throws {
        let e = try XCTUnwrap(decode(#"{"from":"me@x","to":"b@y","cc":"","subject":"Report","date":"d","bytes":9,"attachments":["report.pdf","chart.PNG"]}"#))
        XCTAssertEqual(e.attachmentCount, 2)
        XCTAssertTrue(e.hasImages)
        XCTAssertFalse(try XCTUnwrap(decode(#"{"from":"a","to":"b","subject":"s","date":"d","bytes":1,"attachments":["a.pdf"]}"#)).hasImages)
    }

    func testWrongTypesDropTheFieldNotTheEnvelope() throws {
        let e = try XCTUnwrap(decode(#"{"from":"a","to":"b","subject":"s","date":"d","bytes":1,"snippet":42,"hasAttachments":"yes","attachmentCount":"3","hasImages":null}"#))
        XCTAssertNil(e.snippet)
        XCTAssertFalse(e.hasAttachments)
        XCTAssertEqual(e.attachmentCount, 0)
        XCTAssertFalse(e.hasImages)
        XCTAssertNil(try XCTUnwrap(decode(#"{"from":"a","to":"b","subject":"s","date":"d","bytes":1,"snippet":" \n "}"#)).snippet)
        XCTAssertEqual(try XCTUnwrap(decode(#"{"from":"a","to":"b","subject":"s","date":"d","snippet":"one\n\t two"}"#)).snippet, "one two")
        XCTAssertEqual(try XCTUnwrap(decode(#"{"from":"a","to":"b","subject":"s","date":"d","hasAttachments":true}"#)).attachmentCount, 1)
        XCTAssertEqual(try XCTUnwrap(decode(#"{"from":"a","to":"b","subject":"s","date":"d","attachmentCount":100000}"#)).attachmentCount, 999)
    }

    /// Android drafts never carried `bytes`; the synthesized decoder rejected the whole
    /// envelope, so such a draft showed as "sealed for another device".
    func testAndroidDraftWithoutBytesNowDecodes() throws {
        let e = try XCTUnwrap(decode(#"{"from":"me@mail.oshi-messenger.com","to":"b@y","subject":"draft","date":"Mon Nov 16 12:00:00 CET 2026","snippet":"half written"}"#))
        XCTAssertEqual(e.bytes, 0)
        XCTAssertEqual(e.snippet, "half written")
    }

    func testUnknownKeysAreIgnoredAndRoundTripKeepsThePreview() throws {
        let e = try XCTUnwrap(decode(String(serverNew.dropLast()) + #","previewVersion":2,"labels":["x"]}"#))
        let again = try XCTUnwrap(OSHIMailMessage.Envelope.decode(try JSONEncoder().encode(e)))
        XCTAssertEqual(again.snippet, e.snippet)
        XCTAssertEqual(again.attachmentCount, 1)
    }

    /// Compatibility proof for builds already out (1.0.46 b203 on TestFlight; 1.0.43 in the
    /// store predates OSHI Mail entirely): this is their Envelope, copied verbatim, with the
    /// synthesized Codable. JSONDecoder ignores keys a type does not declare.
    func testThe1046DecoderReadsANewEnvelopeExactlyAsAnOldOne() throws {
        struct Envelope1046: Codable {
            let from: String
            let to: String
            let cc: String?
            let subject: String
            let date: String
            let messageId: String?
            let bytes: Int
        }
        let e = try JSONDecoder().decode(Envelope1046.self, from: Data(serverNew.utf8))
        XCTAssertEqual(e.subject, "Your statement")
        XCTAssertEqual(e.bytes, 838)
    }

    func testDraftSnippetIsCollapsedAndCapped() {
        XCTAssertEqual(MailMessageParser.listSnippet(of: "Hello   there,\n\nsee you"), "Hello there, see you")
        let long = MailMessageParser.listSnippet(of: String(repeating: "word ", count: 100))
        XCTAssertTrue(long.hasSuffix("\u{2026}"))
        XCTAssertLessThanOrEqual(long.count, MailMessageParser.snippetMax + 1)
    }
}
