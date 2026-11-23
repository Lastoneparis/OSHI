package com.oshi.messenger.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presentation rules of the mail reader (plain-text blocks, links, HTML hardening). */
class MailReaderDisplayTest {

    @Test fun `each line keeps its own direction`() {
        val blocks = MailDisplay.textBlocks("Hello\nمرحبا بالعالم\nשלום\nПривет")
        assertEquals(listOf("Hello", "مرحبا بالعالم\nשלום", "Привет"), blocks.map { it.text })
        assertEquals(listOf(false, true, false), blocks.map { it.rtl })
    }

    @Test fun `a neutral line follows the direction before it`() {
        val blocks = MailDisplay.textBlocks("مرحبا\n123 456")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].rtl)
    }

    @Test fun `quotes, nested quotes and the signature are separate blocks`() {
        val body = "Hi,\n\nThanks.\n\nOn Sunday Hugo wrote:\n> Can you?\n>> Earlier.\n\n-- \nAnatole\n+33 6"
        val blocks = MailDisplay.textBlocks(body)
        assertEquals(
            listOf(
                MailDisplay.BlockKind.TEXT, MailDisplay.BlockKind.QUOTE,
                MailDisplay.BlockKind.QUOTE, MailDisplay.BlockKind.SIGNATURE,
            ),
            blocks.map { it.kind },
        )
        assertEquals("Hi,\n\nThanks.\n\nOn Sunday Hugo wrote:", blocks[0].text)
        assertEquals("Can you?", blocks[1].text)
        assertEquals(1, blocks[1].quoteLevel)
        assertEquals("Earlier.", blocks[2].text)
        assertEquals(2, blocks[2].quoteLevel)
        assertEquals("Anatole\n+33 6", blocks[3].text)
        assertTrue(blocks[3].spaceBefore)
        assertFalse(blocks[1].spaceBefore)
    }

    @Test fun `links drop trailing punctuation and recognise www and e-mail`() {
        val text = "See https://example.org/a?b=1&c=2. Or (https://x.io/p) and www.oshi.app, write to bob@example.com!"
        val links = MailDisplay.links(text)
        assertEquals(
            listOf("https://example.org/a?b=1&c=2", "https://x.io/p", "https://www.oshi.app", "mailto:bob@example.com"),
            links.map { it.url },
        )
        assertEquals("https://example.org/a?b=1&c=2", text.substring(links[0].start, links[0].end))
    }

    @Test fun `only web, mail and phone links leave the reader`() {
        assertTrue(MailDisplay.isOpenableLink("https://example.com"))
        assertTrue(MailDisplay.isOpenableLink("mailto:a@b.c"))
        assertTrue(MailDisplay.isOpenableLink("tel:+331"))
        assertFalse(MailDisplay.isOpenableLink("javascript:alert(1)"))
        assertFalse(MailDisplay.isOpenableLink("JavaScript:alert(1)"))
        assertFalse(MailDisplay.isOpenableLink("intent://x#Intent;end"))
        assertFalse(MailDisplay.isOpenableLink("file:///data/data/x"))
        assertFalse(MailDisplay.isOpenableLink("content://x"))
    }

    @Test fun `remote content is detected in every place a fetch can hide`() {
        assertTrue(MailDisplay.hasRemoteContent("<img src=\"https://t.example/p.gif\">"))
        assertTrue(MailDisplay.hasRemoteContent("<img src='//t.example/p.gif'>"))
        assertTrue(MailDisplay.hasRemoteContent("<img srcset=\"https://t.example/a.png 2x\">"))
        assertTrue(MailDisplay.hasRemoteContent("<table background=\"http://t.example/bg.png\">"))
        assertTrue(MailDisplay.hasRemoteContent("<div style=\"background:url( 'https://t.example/bg' )\">"))
        assertTrue(MailDisplay.hasRemoteContent("<link rel=stylesheet href=\"https://t.example/s.css\">"))
        assertFalse(MailDisplay.hasRemoteContent("<img src=\"cid:logo\"><img src=\"data:image/png;base64,AA\"><a href=\"https://x\">x</a>"))
    }

    @Test fun `reader html removes active content and points cid at the local host`() {
        val out = MailDisplay.readerHtml(
            "<html><head><meta http-equiv=\"refresh\" content=\"0;url=https://evil\"><base href=\"https://evil/\">" +
                "<link rel=stylesheet href=\"https://t/s.css\"><script>alert(1)</script></head>" +
                "<body><iframe src=\"https://evil\"></iframe><form action=\"https://evil\"><input></form>" +
                "<img src=\"cid:plan123@oshi\"></body></html>",
            allowRemote = false,
        )
        listOf("<script", "alert(1)", "http-equiv", "<base", "<iframe", "<form", "<link").forEach {
            assertFalse("still contains $it", out.contains(it, ignoreCase = true))
        }
        assertTrue(out.contains("src=\"https://${MailDisplay.CID_HOST}/plan123%40oshi\""))
        assertTrue(out.contains("name=\"viewport\""))
        assertEquals("plan123@oshi", MailDisplay.cidOf("https://${MailDisplay.CID_HOST}/plan123%40oshi"))
        assertNull(MailDisplay.cidOf("https://example.com/plan"))
        // With remote content allowed, a stylesheet link stays.
        assertTrue(MailDisplay.readerHtml("<link rel=stylesheet href=\"https://t/s.css\">", allowRemote = true).contains("<link"))
    }

    @Test fun `a fragment without html or head gets a full document`() {
        val out = MailDisplay.readerHtml("<div>Hey!</div>", allowRemote = false)
        assertTrue(out.startsWith("<!DOCTYPE html><html><head>"))
        assertTrue(out.endsWith("<div>Hey!</div></body></html>"))
    }

    @Test fun `fixed newsletter widths become fluid, small ones and max-width stay`() {
        val html = "<style>.wrapper{width:600px} .x{max-width:600px;min-width:640px}</style>" +
            "<table width=\"600\" style=\"width:600px\"><tr><td width=\"276\">a</td></tr></table><img width=\"552\">"
        val out = MailDisplay.fluidWidths(html)
        assertTrue(out.contains(".wrapper{width:100%;max-width:600px}"))
        assertTrue(out.contains("max-width:600px;min-width:0"))
        assertTrue(out.contains("<table width=\"100%\" style=\"width:100%;max-width:600px\">"))
        assertTrue(out.contains("<td width=\"276\">"))
        assertTrue(out.contains("<img width=\"552\">"))
    }

    @Test fun `referenced cids are found so inline images are not listed twice`() {
        assertEquals(setOf("a@b", "logo"), MailDisplay.referencedCids("<img src=\"cid:a@b\"><img src='cid:logo'>"))
    }

    @Test fun `sender key ignores display name and case and is not the address`() {
        val k = MailDisplay.senderKey("Sam Lee <Sam@Example.com>")
        assertEquals(k, MailDisplay.senderKey("sam@example.com"))
        assertEquals(64, k.length)
        assertFalse(k.contains("example"))
    }
}
