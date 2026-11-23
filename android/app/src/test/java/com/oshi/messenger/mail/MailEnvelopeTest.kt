package com.oshi.messenger.mail

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sealed list envelope, decoded. The server now adds `snippet`, `hasAttachments`,
 * `attachmentCount` and `hasImages`; they are optional and additive, so envelopes from
 * before the change, from older drafts, and malformed values must all still decode.
 */
class MailEnvelopeTest {

    private fun parse(json: String) = OshiMailClient.EnvelopeJson.parseEnvelope(json)

    // Verbatim output of the patched server (lmtp.parseEnvelope + mail_preview.preview)
    // for scratchpad/mail-server/samples/03_related_cid_and_pdf.eml.
    private val serverNew = """{"from":"Bank <noreply@bank.example>","to":"hugo@mail.oshi-messenger.com","cc":"","subject":"Your statement","date":"Mon, 16 Nov 2026 11:19:40 GMT","messageId":"","bytes":838,"envelopeFrom":"a@b","envelopeTo":"hugo@mail.oshi-messenger.com","hasAttachments":true,"attachmentCount":1,"hasImages":true,"snippet":"Your November statement is ready."}"""

    // What the server has delivered until now (no preview fields).
    private val serverOld = """{"from":"Alice <a@x.io>","to":"hugo@mail.oshi-messenger.com","cc":"","subject":"Lunch?","date":"Mon, 9 Nov 2026 10:00:00 +0000","messageId":"<1@x>","bytes":512,"envelopeFrom":"a@x.io","envelopeTo":"hugo@mail.oshi-messenger.com"}"""

    @Test fun `new envelope carries snippet and attachment flags`() {
        val e = parse(serverNew)
        assertEquals("Your statement", e.subject)
        assertEquals("Your November statement is ready.", e.snippet)
        assertTrue(e.hasAttachments)
        assertEquals(1, e.attachmentCount)
        assertTrue(e.hasImages)
    }

    @Test fun `old envelope decodes exactly as before, with no preview`() {
        val e = parse(serverOld)
        assertEquals(OshiMailClient.Envelope("Alice <a@x.io>", "hugo@mail.oshi-messenger.com", "Lunch?", "Mon, 9 Nov 2026 10:00:00 +0000", ""), e)
        assertNull(e.snippet)
        assertFalse(e.hasAttachments)
        assertEquals(0, e.attachmentCount)
        assertFalse(e.hasImages)
    }

    @Test fun `old Sent copies still get a paperclip from their filename list`() {
        val e = parse("""{"from":"me@x","to":"b@y","cc":"","subject":"Report","date":"d","attachments":["report.pdf","chart.PNG"]}""")
        assertTrue(e.hasAttachments)
        assertEquals(2, e.attachmentCount)
        assertTrue(e.hasImages)
        val noImage = parse("""{"from":"me@x","to":"b@y","subject":"s","date":"d","attachments":["a.pdf"]}""")
        assertFalse(noImage.hasImages)
        val none = parse("""{"from":"me@x","to":"b@y","subject":"s","date":"d","attachments":[]}""")
        assertFalse(none.hasAttachments)
    }

    @Test fun `wrong types, nulls, blanks and absurd counts degrade instead of failing the row`() {
        val e = parse("""{"from":"a","to":"b","subject":"s","date":"d","snippet":42,"hasAttachments":"yes","attachmentCount":"3","hasImages":null}""")
        assertNull(e.snippet)
        assertFalse(e.hasAttachments)
        assertEquals(0, e.attachmentCount)
        assertFalse(e.hasImages)
        assertNull(parse("""{"from":"a","to":"b","subject":"s","date":"d","snippet":"  \n "}""").snippet)
        assertEquals(999, parse("""{"from":"a","to":"b","subject":"s","date":"d","attachmentCount":1000000}""").attachmentCount)
        assertEquals(0, parse("""{"from":"a","to":"b","subject":"s","date":"d","attachmentCount":-4}""").attachmentCount)
        // A flag without a count still shows one paperclip.
        assertEquals(1, parse("""{"from":"a","to":"b","subject":"s","date":"d","hasAttachments":true}""").attachmentCount)
        assertEquals("one two", parse("""{"from":"a","to":"b","subject":"s","date":"d","snippet":"one\n\t two"}""").snippet)
    }

    @Test fun `unknown future keys are ignored`() {
        val e = parse(serverNew.dropLast(1) + ""","previewVersion":2,"labels":["x"],"nested":{"a":1}}""")
        assertEquals("Your November statement is ready.", e.snippet)
    }

    /**
     * Compatibility proof for apps already installed: this is the decoder of
     * OshiMailClient.inbox() as shipped in 1.6.96 (origin/release/1.6.96), copied
     * verbatim. org.json reads keys by name and ignores the rest, so the new fields
     * cannot change what an old app shows or make it throw.
     */
    @Test fun `the 1_6_96 decoder reads a new envelope exactly as an old one`() {
        fun decode196(json: String): OshiMailClient.Envelope {
            val plain = JSONObject(json)
            return OshiMailClient.Envelope(
                from = plain.optString("from"),
                to = plain.optString("to"),
                subject = plain.optString("subject"),
                date = plain.optString("date"),
                cc = plain.optString("cc"),
            )
        }
        val e = decode196(serverNew)
        assertEquals("Bank <noreply@bank.example>", e.from)
        assertEquals("Your statement", e.subject)
        assertNull(e.snippet)
    }

    @Test fun `list snippet prefers the envelope and falls back to an opened body`() {
        assertEquals("from server", MailDisplay.listSnippet("from server", "Subject\nFrom\nopened body"))
        assertEquals("opened body", MailDisplay.listSnippet(null, "Subject\nFrom\nopened body"))
        assertEquals("opened body", MailDisplay.listSnippet("  ", "Subject\nFrom\nopened body"))
        assertNull(MailDisplay.listSnippet(null, null))
    }

    @Test fun `draft snippet is collapsed and capped at a word`() {
        assertEquals("Hello there, see you", MailDisplay.snippetOf("Hello   there,\n\nsee you"))
        val long = MailDisplay.snippetOf("word ".repeat(100))
        assertTrue(long.endsWith("…"))
        assertTrue(long.codePointCount(0, long.length) <= MailDisplay.SNIPPET_MAX + 1)
        assertEquals("1 attachment, contains images", MailDisplay.attachmentDescription(1, true))
        assertEquals("3 attachments", MailDisplay.attachmentDescription(3, false))
        assertNull(MailDisplay.attachmentDescription(0, false))
    }
}
