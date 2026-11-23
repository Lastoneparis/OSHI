package com.oshi.messenger.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two real bugs found by reading actual mail through the web client, fixed identically
 * here, in mime.js, and in MailMessageParser.swift:
 *
 *  1. The message is parsed as ISO-8859-1 so attachments survive byte-for-byte, but that
 *     means a header carrying RAW UTF-8 with no RFC 2047 encoded-word — real senders do
 *     this — arrives as Latin-1 mojibake ("Boutique LumiÃ¨re" instead of "Boutique
 *     Lumière"). [MailMime] re-decodes such a header strictly as UTF-8 and keeps the
 *     original only when that strict decode fails, so a genuine Latin-1 header survives.
 *  2. `stripHtml` only ended a line at `</p>`, so a heading immediately before a paragraph
 *     ran into it ("Merci !Votre commande…"). Every block element must end a line.
 */
class MailMimeTest {

    private fun rawUtf8Message(headerLine: String, body: String = "Hello"): ByteArray =
        "$headerLine\r\n\r\n$body".toByteArray(Charsets.UTF_8)

    private fun rawLatin1Message(headerLine: String, body: String = "Hello"): ByteArray =
        "$headerLine\r\n\r\n$body".toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `a display name written as raw UTF-8 with no encoded-word is recovered`() {
        val mail = MailMime.parse(rawUtf8Message("From: Boutique Lumière <x@y.com>"))
        assertEquals("Boutique Lumière <x@y.com>", mail.from)
    }

    @Test
    fun `a raw UTF-8 subject with no encoded-word is recovered`() {
        val mail = MailMime.parse(rawUtf8Message("Subject: Café — accentué"))
        assertEquals("Café — accentué", mail.subject)
    }

    @Test
    fun `a genuine Latin-1 header is not mangled by the UTF-8 recovery`() {
        // 0xE9 (Latin-1 'é') followed by ASCII is not a valid UTF-8 sequence, so the
        // strict decode must fail and the original Latin-1 text must survive untouched.
        val mail = MailMime.parse(rawLatin1Message("Subject: café au lait"))
        assertEquals("café au lait", mail.subject)
    }

    @Test
    fun `a plain ASCII header is unaffected by the UTF-8 recovery`() {
        val mail = MailMime.parse(rawUtf8Message("Subject: Your receipt"))
        assertEquals("Your receipt", mail.subject)
    }

    @Test
    fun `an RFC 2047 encoded-word subject still decodes after the UTF-8 recovery change`() {
        // "Café" base64-encoded as UTF-8: Q2Fmw6k=
        val mail = MailMime.parse(rawUtf8Message("Subject: =?UTF-8?B?Q2Fmw6k=?="))
        assertEquals("Café", mail.subject)
    }

    @Test
    fun `a heading immediately before a paragraph gets a line break between them`() {
        val html = "<h1>Merci !</h1><p>Votre commande…</p>"
        assertEquals("Merci !\n\nVotre commande…", MailMime.stripHtml(html))
    }

    @Test
    fun `list items are separated rather than run together`() {
        val html = "<ul><li>One</li><li>Two</li></ul>"
        assertEquals("One\n\nTwo", MailMime.stripHtml(html))
    }

    @Test
    fun `table rows are separated rather than run together`() {
        val html = "<table><tr><td>A</td></tr><tr><td>B</td></tr></table>"
        assertEquals("A\n\nB", MailMime.stripHtml(html))
    }

    @Test
    fun `a lone paragraph still gets a trailing break stripped`() {
        assertEquals("Hello", MailMime.stripHtml("<p>Hello</p>"))
    }

    // ---- legacy / ANSI charsets ------------------------------------------------

    private fun msg(charset: String, body: ByteArray, encoding: String = "8bit"): ByteArray =
        "From: a@b.com\r\nSubject: T\r\nContent-Type: text/plain; charset=$charset\r\nContent-Transfer-Encoding: $encoding\r\n\r\n"
            .toByteArray(Charsets.ISO_8859_1) + body

    /**
     * Windows "ANSI". 0x80 is the euro sign and 0x93/0x94 are curly quotes; in ISO-8859-1
     * those same bytes are control characters, so mapping 1252 onto Latin-1 silently turns
     * prices and quotes into invisible characters.
     */
    @Test
    fun `windows-1252 body decodes`() {
        val bytes = byteArrayOf(0x50, 0x72, 0x69, 0x78, 0x20, 0x31, 0x30, 0x30, 0x80.toByte(), 0x20, 0x93.toByte(), 0x6F, 0x6B, 0x94.toByte())
        assertEquals("Prix 100€ “ok”", MailMime.parse(msg("windows-1252", bytes)).text)
    }

    /** iso-8859-15 differs from -1 where it matters: 0xA4 is the euro sign, not ¤. */
    @Test
    fun `iso-8859-15 euro is not the currency sign`() {
        assertEquals("Prix €", MailMime.parse(msg("iso-8859-15", byteArrayOf(0x50, 0x72, 0x69, 0x78, 0x20, 0xA4.toByte()))).text)
    }

    @Test
    fun `plain ascii and latin-1 still work`() {
        assertEquals("Plain ASCII", MailMime.parse(msg("us-ascii", "Plain ASCII".toByteArray())).text)
        assertEquals("Café", MailMime.parse(msg("iso-8859-1", byteArrayOf(0x43, 0x61, 0x66, 0xE9.toByte()))).text)
    }

    @Test
    fun `non-latin legacy charsets`() {
        assertEquals("Привет",
            MailMime.parse(msg("koi8-r", byteArrayOf(0xF0.toByte(), 0xD2.toByte(), 0xC9.toByte(), 0xD7.toByte(), 0xC5.toByte(), 0xD4.toByte()))).text)
        assertEquals("Zelená",
            MailMime.parse(msg("iso-8859-2", byteArrayOf(0x5A, 0x65, 0x6C, 0x65, 0x6E, 0xE1.toByte()))).text)
    }

    /** A windows-1252 subject — Outlook's default — used to show as its raw source. */
    @Test
    fun `encoded-word in a legacy charset`() {
        assertEquals("Prix €100", MailMime.decodeEncodedWords("=?windows-1252?Q?Prix_=80100?="))
        assertEquals("Zelená", MailMime.decodeEncodedWords("=?ISO-8859-2?Q?Zelen=E1?="))
    }

    /** An unknown label must not lose the message. */
    @Test
    fun `unknown charset falls back instead of failing`() {
        assertEquals("hi", MailMime.parse(msg("x-nonsense", "hi".toByteArray())).text)
    }
}
