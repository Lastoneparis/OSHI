package com.oshi.messenger

import com.oshi.messenger.ui.components.BodyMarkup
import com.oshi.messenger.util.MessageFormatting
import com.oshi.messenger.util.MessageFormatting.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __TEXT_FORMATTING_2026_11_24__ `**bold**` / `__underline__`. The golden vectors are
 * the shared Android + iOS contract (text-formatting spec, 24/11): the SAME table lives in
 * the iOS suite, so a change here without the same change there is a cross-platform bug.
 */
class MessageFormattingTest {

    private data class Golden(val input: String, val text: String, val bold: List<Range>, val underline: List<Range>)

    private fun r(a: Int, b: Int) = Range(a, b)

    private val golden = listOf(
        Golden("hello", "hello", listOf(), listOf()),
        Golden("**hi**", "hi", listOf(r(0, 2)), listOf()),
        Golden("__hi__", "hi", listOf(), listOf(r(0, 2))),
        Golden("a **b** c", "a b c", listOf(r(2, 3)), listOf()),
        Golden("**__x__**", "x", listOf(r(0, 1)), listOf(r(0, 1))),
        Golden("__**x**__", "x", listOf(r(0, 1)), listOf(r(0, 1))),
        Golden("**a** and __b__", "a and b", listOf(r(0, 1)), listOf(r(6, 7))),
        Golden("** a**", "** a**", listOf(), listOf()),
        Golden("**a **", "**a **", listOf(), listOf()),
        Golden("****", "****", listOf(), listOf()),
        Golden("**a\nb**", "**a\nb**", listOf(), listOf()),
        Golden("**unclosed", "**unclosed", listOf(), listOf()),
        Golden("2**3 and 4**5", "23 and 45", listOf(r(1, 8)), listOf()),
        Golden("https://x.com/a__b__c", "https://x.com/a__b__c", listOf(), listOf()),
        Golden("snake_case_name", "snake_case_name", listOf(), listOf()),
    )

    @Test fun goldenVectors() {
        for (g in golden) {
            val out = MessageFormatting.parse(g.input)
            assertEquals("text of ${g.input}", g.text, out.text)
            assertEquals("bold of ${g.input}", g.bold, out.bold)
            assertEquals("underline of ${g.input}", g.underline, out.underline)
            assertEquals("strip of ${g.input}", g.text, MessageFormatting.strip(g.input))
        }
    }

    @Test fun formattedLinkStaysALink() {
        val out = MessageFormatting.parse("**https://x.com/a__b**")
        assertEquals("https://x.com/a__b", out.text)
        assertEquals(listOf(r(0, 18)), out.bold)
        assertEquals(listOf<Range>(), out.underline)
    }

    @Test fun hasFormatting() {
        assertTrue(MessageFormatting.hasFormatting("a **b**"))
        assertFalse(MessageFormatting.hasFormatting("****"))
        assertFalse(MessageFormatting.hasFormatting("plain"))
    }

    @Test fun rangesAreUtf16() {
        val out = MessageFormatting.parse("😀 **é😀**")
        assertEquals("😀 é😀", out.text)
        assertEquals(listOf(r(3, 6)), out.bold)
    }

    // --- composer action ---

    @Test fun noSelectionInsertsEmptyPairWithCursorBetween() {
        val e = MessageFormatting.toggle("hi ", 3, 3, MessageFormatting.BOLD)
        assertEquals(MessageFormatting.Edit("hi ****", 5, 5), e)
        val u = MessageFormatting.toggle("", 0, 0, MessageFormatting.UNDERLINE)
        assertEquals(MessageFormatting.Edit("____", 2, 2), u)
    }

    @Test fun selectionIsWrappedAndInnerTextStaysSelected() {
        val e = MessageFormatting.toggle("say hello now", 4, 9, MessageFormatting.BOLD)
        assertEquals(MessageFormatting.Edit("say **hello** now", 6, 11), e)
        assertEquals("say hello now", MessageFormatting.parse(e.text).text)
        // Reversed selection (anchor after cursor) behaves the same.
        assertEquals(e, MessageFormatting.toggle("say hello now", 9, 4, MessageFormatting.BOLD))
    }

    @Test fun edgeSpacesStayOutsideTheMarkers() {
        val e = MessageFormatting.toggle("say hello now", 3, 10, MessageFormatting.UNDERLINE)
        assertEquals(MessageFormatting.Edit("say __hello__ now", 6, 11), e)
        assertEquals(listOf(r(4, 9)), MessageFormatting.parse(e.text).underline)
    }

    @Test fun togglingTwiceRemovesTheMarkers() {
        val on = MessageFormatting.toggle("say hello now", 4, 9, MessageFormatting.BOLD)
        val off = MessageFormatting.toggle(on.text, on.selectionStart, on.selectionEnd, MessageFormatting.BOLD)
        assertEquals(MessageFormatting.Edit("say hello now", 4, 9), off)
        // Selection that includes the markers unwraps too.
        val off2 = MessageFormatting.toggle("say **hello** now", 4, 13, MessageFormatting.BOLD)
        assertEquals(MessageFormatting.Edit("say hello now", 4, 9), off2)
    }

    @Test fun boldThenUnderlineNests() {
        val b = MessageFormatting.toggle("x", 0, 1, MessageFormatting.BOLD)
        val bu = MessageFormatting.toggle(b.text, b.selectionStart, b.selectionEnd, MessageFormatting.UNDERLINE)
        assertEquals("**__x__**", bu.text)
        val p = MessageFormatting.parse(bu.text)
        assertEquals(listOf(r(0, 1)), p.bold)
        assertEquals(listOf(r(0, 1)), p.underline)
    }

    @Test fun multiLineSelectionIsWrappedLineByLine() {
        val e = MessageFormatting.toggle("one\ntwo", 0, 7, MessageFormatting.BOLD)
        assertEquals("**one**\n**two**", e.text)
        assertEquals(listOf(r(0, 3), r(4, 7)), MessageFormatting.parse(e.text).bold)
    }

    @Test fun blankSelectionInsertsPair() {
        val e = MessageFormatting.toggle("a  b", 1, 3, MessageFormatting.BOLD)
        assertEquals(MessageFormatting.Edit("a  ****b", 5, 5), e)
    }

    // --- bubble pipeline (with the older single-char markup) ---

    @Test fun bodyMarkupKeepsUrlsIntactAndMergesInlineMarkup() {
        val url = BodyMarkup.render("https://x.com/a__b__c")
        assertEquals("https://x.com/a__b__c", url.text)
        assertTrue(url.spans.isEmpty())

        val mixed = BodyMarkup.render("**bold** _it_ __u__")
        assertEquals("bold it u", mixed.text)
        assertEquals(
            listOf(
                BodyMarkup.Span(BodyMarkup.Kind.BOLD, 0, 4),
                BodyMarkup.Span(BodyMarkup.Kind.ITALIC, 5, 7),
                BodyMarkup.Span(BodyMarkup.Kind.UNDERLINE, 8, 9),
            ),
            mixed.spans,
        )

        // A single-char run INSIDE a bold span: the bold range shrinks with the dropped markers.
        val inner = BodyMarkup.render("**a _b_ c**")
        assertEquals("a b c", inner.text)
        assertTrue(BodyMarkup.Span(BodyMarkup.Kind.BOLD, 0, 5) in inner.spans)
        assertTrue(BodyMarkup.Span(BodyMarkup.Kind.ITALIC, 2, 3) in inner.spans)

        for (g in golden) {
            // No golden input carries single-char markup, so the bubble text is the golden text.
            assertEquals(g.input, g.text, BodyMarkup.render(g.input).text)
        }
    }
}
