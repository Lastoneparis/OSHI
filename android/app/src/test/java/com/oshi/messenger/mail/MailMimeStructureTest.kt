package com.oshi.messenger.mail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

/**
 * Real-world message shapes found by rendering a fixture set on the API 36 emulator
 * (2026-11-16): each of these was wrong on screen before the byte-level walker.
 */
class MailMimeStructureTest {

    private fun crlf(s: String) = s.trimIndent().replace("\n", "\r\n").toByteArray(Charsets.ISO_8859_1)

    /** A 17 MB photo came out as "0 B": the String-based walker ran out of heap. */
    @Test fun `a multi-megabyte base64 attachment decodes byte for byte`() {
        val photo = Random(42).nextBytes(6 * 1024 * 1024)
        val b64 = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(photo)
        val raw = crlf(
            """
            From: a@b.c
            Content-Type: multipart/mixed; boundary="B1"

            --B1
            Content-Type: text/plain; charset=utf-8

            Photos attached.
            --B1
            Content-Type: image/jpeg
            Content-Transfer-Encoding: base64
            Content-Disposition: attachment; filename="IMG_4021.JPG"

            """,
        ) + "\r\n\r\n".toByteArray() + b64.toByteArray() + "\r\n--B1--\r\n".toByteArray()
        val mail = MailMime.parse(raw)
        assertEquals("Photos attached.", mail.text)
        assertEquals(1, mail.attachments.size)
        assertEquals("IMG_4021.JPG", mail.attachments[0].filename)
        assertArrayEquals(photo, mail.attachments[0].bytes)
        assertTrue(mail.attachments[0].isImage)
    }

    @Test fun `RFC 2231 filenames, single and continued, are decoded`() {
        assertEquals("résumé ✓.txt", MailMime.params("attachment; filename*=UTF-8''r%C3%A9sum%C3%A9%20%E2%9C%93.txt")["filename"])
        assertEquals(
            "notes de réunion.docx",
            MailMime.params("attachment; filename*0*=utf-8''notes%20de%20r%C3%A9; filename*1*=union.docx")["filename"],
        )
        assertEquals("plain;name.pdf", MailMime.params("attachment; filename=\"plain;name.pdf\"")["filename"])
    }

    @Test fun `an RFC 2047 encoded filename inside quotes is decoded`() {
        val raw = crlf(
            """
            Content-Type: multipart/mixed; boundary=X

            --X
            Content-Type: application/pdf; name="=?UTF-8?B?RmFjdHVyZS3DqXTDqS0yMDI2LnBkZg==?="
            Content-Disposition: attachment; filename="=?UTF-8?B?RmFjdHVyZS3DqXTDqS0yMDI2LnBkZg==?="
            Content-Transfer-Encoding: base64

            JVBERi0=
            --X--
            """,
        )
        assertEquals("Facture-été-2026.pdf", MailMime.parse(raw).attachments.single().filename)
    }

    /** Windows mail labels cp1252 as iso-8859-1; the euro sign (0x80) was a box. */
    @Test fun `a body labelled iso-8859-1 decodes 0x80 as the euro sign`() {
        val raw = crlf("Content-Type: text/plain; charset=ISO-8859-1\nContent-Transfer-Encoding: quoted-printable\n\n120,00 =80 pay=E9e")
        assertEquals("120,00 € payée", MailMime.parse(raw).text)
    }

    /** Inline `cid:` images were dropped entirely — not shown, not even listed. */
    @Test fun `multipart related keeps the html and its inline images with their content ids`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val raw = crlf(
            """
            Content-Type: multipart/related; boundary="R"

            --R
            Content-Type: multipart/alternative; boundary="A"

            --A
            Content-Type: text/plain; charset=utf-8

            Voici le plan.
            --A
            Content-Type: text/html; charset=utf-8

            <p>Voici le plan.</p><img src="cid:plan123@oshi">
            --A--

            --R
            Content-Type: image/png
            Content-ID: <plan123@oshi>
            Content-Disposition: inline; filename="plan.png"
            Content-Transfer-Encoding: base64

            ${Base64.getEncoder().encodeToString(png)}
            --R
            Content-Type: image/png
            Content-ID: <logo456>
            Content-Transfer-Encoding: base64

            ${Base64.getEncoder().encodeToString(png)}
            --R--
            """,
        )
        val mail = MailMime.parse(raw)
        assertEquals("Voici le plan.", mail.text)
        assertTrue(mail.html!!.contains("cid:plan123@oshi"))
        assertEquals(2, mail.attachments.size)
        val plan = mail.attachments[0]
        assertEquals("plan123@oshi", plan.contentId)
        assertTrue(plan.inline)
        assertEquals("plan.png", plan.filename)
        assertArrayEquals(png, plan.bytes)
        val logo = mail.attachments[1]
        assertEquals("logo456", logo.contentId)
        assertTrue(logo.inline)
        assertEquals("image-2.png", logo.filename)
    }

    @Test fun `a calendar part is an attachment, not the message body`() {
        val raw = crlf(
            """
            Content-Type: multipart/mixed; boundary=M

            --M
            Content-Type: text/plain

            You are invited.
            --M
            Content-Type: text/calendar; method=REQUEST; charset=utf-8

            BEGIN:VCALENDAR
            END:VCALENDAR
            --M--
            """,
        )
        val mail = MailMime.parse(raw)
        assertEquals("You are invited.", mail.text)
        assertEquals("invite.ics", mail.attachments.single().filename)
        assertEquals("text/calendar", mail.attachments.single().mimeType)
    }

    @Test fun `a preamble is not a part and a boundary mid-line is not a delimiter`() {
        val raw = crlf(
            """
            Content-Type: multipart/mixed; boundary=Q

            This is a multi-part message in MIME format.

            --Q
            Content-Type: text/plain

            see --Q in the middle of a line
            --Q--
            """,
        )
        assertEquals("see --Q in the middle of a line", MailMime.parse(raw).text)
    }

    @Test fun `text parts around an attachment are all kept`() {
        val raw = crlf(
            """
            Content-Type: multipart/mixed; boundary=T

            --T
            Content-Type: text/plain

            Before the photo.
            --T
            Content-Type: image/gif
            Content-Disposition: inline; filename=a.gif

            GIF89a
            --T
            Content-Type: text/plain

            After the photo.
            --T--
            """,
        )
        val mail = MailMime.parse(raw)
        assertEquals("Before the photo.\n\nAfter the photo.", mail.text)
        assertEquals("a.gif", mail.attachments.single().filename)
    }

    @Test fun `a single-part non-text message is an attachment, not garbage text`() {
        val raw = crlf("Content-Type: application/pdf; name=x.pdf\nContent-Transfer-Encoding: base64\n\nJVBERi0=")
        val mail = MailMime.parse(raw)
        assertEquals("", mail.text)
        assertEquals("x.pdf", mail.attachments.single().filename)
        assertArrayEquals("%PDF-".toByteArray(), mail.attachments.single().bytes)
    }

    @Test fun `stripHtml decodes entities, ignores source line breaks and keeps header text`() {
        val html = "<header>Top</header>\n<p>L&#8217;&eacute;quipe\n   vous   salue&nbsp;!</p><!-- hidden -->" +
            "<p>A &amp; B &rarr; &#x2713;</p><style>p{}</style>"
        assertEquals("Top\n\nL’équipe vous salue !\n\nA & B → ✓", MailMime.stripHtml(html))
    }

    @Test fun `stripHtml drops invisible preheader padding characters`() {
        assertEquals("Tips", MailMime.stripHtml("<div>Tips&zwnj;&nbsp;͏‌</div>"))
    }

    @Test fun `html-only message falls back to readable text`() {
        val raw = crlf("Content-Type: text/html; charset=utf-8\n\n<h2>Alert</h2><p>New sign-in</p>")
        val mail = MailMime.parse(raw)
        assertEquals("Alert\n\nNew sign-in", mail.text)
        assertNotNull(mail.html)
    }

    @Test fun `a plain message has no html`() {
        assertNull(MailMime.parse(crlf("Subject: x\n\nhello")).html)
        assertFalse(MailMime.parse(crlf("Subject: x\n\nhello")).attachments.any())
    }
}
