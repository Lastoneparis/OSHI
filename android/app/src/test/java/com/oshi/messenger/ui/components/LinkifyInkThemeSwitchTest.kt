package com.oshi.messenger.ui.components

import androidx.compose.ui.graphics.Color
import com.oshi.messenger.SourceText
import com.oshi.messenger.ui.theme.BubbleAccent
import com.oshi.messenger.ui.theme.Contrast
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.theme.oshiColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * __INK_SURVIVES_THEME_SWITCH_2026_11_21__
 *
 * Flip light/dark with a chat open: the bubbles repainted with the new scheme but
 * the message text kept the OLD ink — white on the white light bubble, black on the
 * dark one — until the screen was reopened. `LinkifyText` cached an AnnotatedString
 * with the body ink baked into every span, keyed on the text alone; MainActivity
 * handles uiMode itself, so nothing was recreated and the cache survived the switch.
 *
 * The cached string now carries no body colour (the ink is applied through the text
 * style on every recomposition, by the same caller that paints the bubble), and the
 * one colour it does carry — the link ink — is a cache key.
 */
class LinkifyInkThemeSwitchTest {

    // A plain-JVM stand-in for android.util.Patterns.WEB_URL (a stub in unit tests).
    private val url = Pattern.compile("""(https?://)?(www\.)?[a-z0-9-]+\.[a-z]{2,}(/\S*)?""")

    private val body = "Hello *there*, see https://oshi.example/x and @AAPL today"

    /** Every colour that appears in a span of [s]. */
    private fun spanColours(s: androidx.compose.ui.text.AnnotatedString): Set<Color> =
        s.spanStyles.map { it.item.color }.filter { it != Color.Unspecified }.toSet()

    @Test
    fun `the cached body string carries no body ink, only the link ink`() {
        val light = Oshi.brandInk(darkTheme = false)
        val dark = Oshi.brandInk(darkTheme = true)
        val a = buildLinkAnnotatedString(body, light, urlPattern = url)
        val b = buildLinkAnnotatedString(body, dark, urlPattern = url)
        // Links / tickers were found at all — otherwise the next asserts are vacuous.
        assertTrue("no link span was built", a.getStringAnnotations("URL", 0, a.length).size >= 2)
        assertEquals(setOf(light), spanColours(a))
        assertEquals(setOf(dark), spanColours(b))
        assertEquals("text must not change with the theme", a.text, b.text)
    }

    @Test
    fun `plain text has no colour span at all`() {
        val s = buildLinkAnnotatedString("Message #57", Color.Black, urlPattern = url)
        assertTrue(spanColours(s).isEmpty())
    }

    /**
     * The switch itself, for every scheme the app ships (light, light glass, dark,
     * OLED) and every transition between them: after the toggle the body ink and the
     * link ink the bubble is drawn with must read ≥ 4.5:1 on the bubble that is now
     * painted. Before the fix the ink was the one from BEFORE the toggle; measuring
     * that stale pair gives ~1:1, which the last assertion pins down.
     */
    @Test
    fun `after any theme switch every bubble keeps its text at 4,5 to 1`() {
        data class Scheme(val name: String, val dark: Boolean, val oled: Boolean, val glass: Boolean)
        val schemes = listOf(
            Scheme("light", false, false, false),
            Scheme("light-glass", false, false, true),
            Scheme("dark", true, false, false),
            Scheme("dark-glass", true, false, true),
            Scheme("oled", true, true, false),
        )
        // (bubble, body ink, link ink) as ChatScreen / GroupChatScreen pick them.
        fun pairs(s: Scheme): List<Triple<String, Color, Pair<Color, Color>>> {
            val received = Oshi.Bubble.received(s.dark, s.oled)
            val onSurface = oshiColorScheme(s.dark, s.oled, s.glass).onSurface
            val out = mutableListOf(
                Triple("received (1:1, group, channel post)", received,
                    onSurface to Oshi.brandInk(Contrast.luminance(onSurface) > 0.5)),
            )
            for (accent in BubbleAccent.entries) {
                val (a, b) = accent.surfaceStops(s.dark)
                for ((i, stop) in listOf(a, b).withIndex()) {
                    out += Triple("outgoing ${accent.name} stop $i", stop,
                        accent.ink(s.dark) to accent.linkInk(s.dark))
                }
            }
            return out
        }
        for (from in schemes) for (to in schemes) {
            for ((label, bubble, inks) in pairs(to)) {
                val (ink, link) = inks
                val r = Contrast.ratio(ink, bubble)
                assertTrue("${from.name}→${to.name} $label body ink reads $r", r >= 4.5)
                val l = Contrast.ratio(link, bubble)
                assertTrue("${from.name}→${to.name} $label link ink reads $l", l >= 4.5)
                // The ink and the link are what the composable now uses: the body
                // through the style, the link through a string keyed on it.
                val s = buildLinkAnnotatedString(body, link, urlPattern = url)
                assertTrue(spanColours(s).all { it == link })
            }
        }
        // What the bug actually showed: dark ink kept on the light received bubble.
        val stale = Contrast.ratio(
            oshiColorScheme(dark = true).onSurface, Oshi.Bubble.received(false)
        )
        assertTrue("the stale pair should be the illegible one ($stale)", stale < 1.5)
    }

    /** The composable must key the cache on the link ink and apply the body ink live. */
    @Test
    fun `LinkifyText applies the body ink live and keys the cache on the link ink`() {
        val src = SourceText.read("src/main/java/com/oshi/messenger/ui/components/LinkPreview.kt")
        assertTrue(src.contains("remember(shown, addressRanges, linkColor)"))
        assertTrue(src.contains("LocalTextStyle.current.copy(color = color, fontWeight = fontWeight)"))
        assertTrue(
            "the body ink is being baked into a span again",
            !src.contains("SpanStyle(color = textColor)"),
        )
    }
}
