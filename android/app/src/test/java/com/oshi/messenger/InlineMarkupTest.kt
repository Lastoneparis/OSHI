package com.oshi.messenger

import com.oshi.messenger.ui.components.InlineMarkup
import com.oshi.messenger.ui.components.InlineMarkup.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the Android inline-markup parser to iOS InlineMarkup.swift behaviour, so
 * Android styles EXACTLY the runs iOS styles on the same message — including the
 * false-positive protections that keep ordinary text intact.
 */
class InlineMarkupTest {

    private fun parse(s: String) = InlineMarkup.parse(s)

    @Test fun `bold strips markers and spans the inner`() {
        val (clean, spans) = parse("*bold*")
        assertEquals("bold", clean)
        assertEquals(1, spans.size)
        assertEquals(Kind.BOLD, spans[0].kind)
        assertEquals(0, spans[0].location); assertEquals(4, spans[0].length)
    }

    @Test fun `each marker maps to its kind`() {
        assertEquals(Kind.ITALIC, parse("_italic_").second.single().kind)
        assertEquals(Kind.STRIKETHROUGH, parse("~strike~").second.single().kind)
        assertEquals(Kind.CODE, parse("`code`").second.single().kind)
    }

    @Test fun `bold inside a sentence spans only the word`() {
        val (clean, spans) = parse("hello *world* ok")
        assertEquals("hello world ok", clean)
        assertEquals(1, spans.size)
        assertEquals(Kind.BOLD, spans[0].kind)
        assertEquals("world", clean.substring(spans[0].location, spans[0].location + spans[0].length))
    }

    @Test fun `snake_case is never italic`() {
        val (clean, spans) = parse("open file_name_with_underscores now")
        assertEquals("open file_name_with_underscores now", clean)  // untouched
        assertTrue(spans.isEmpty())
    }

    @Test fun `arithmetic with stars keeps every character`() {
        val (clean, spans) = parse("2 * 3 * 4 = 24")
        assertEquals("2 * 3 * 4 = 24", clean)  // no chars deleted
        assertTrue(spans.isEmpty())
    }

    @Test fun `a mid-word star is literal`() {
        val (clean, spans) = parse("a*b*c")
        assertEquals("a*b*c", clean)
        assertTrue(spans.isEmpty())
    }

    @Test fun `empty markers are not styled`() {
        assertEquals("**", parse("**").first)
        assertTrue(parse("**").second.isEmpty())
    }

    @Test fun `markers do not span across a newline`() {
        val (clean, spans) = parse("*start\nend*")
        assertEquals("*start\nend*", clean)
        assertTrue(spans.isEmpty())
    }

    @Test fun `plain text is returned unchanged with no spans`() {
        val (clean, spans) = parse("just a normal message")
        assertEquals("just a normal message", clean)
        assertTrue(spans.isEmpty())
    }

    @Test fun `two runs in one message`() {
        val (clean, spans) = parse("*a* and _b_")
        assertEquals("a and b", clean)
        assertEquals(2, spans.size)
        assertEquals(Kind.BOLD, spans[0].kind)
        assertEquals(Kind.ITALIC, spans[1].kind)
    }
}
